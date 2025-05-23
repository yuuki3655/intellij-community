// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs.update;

import com.intellij.configurationStore.StoreReloadManager;
import com.intellij.history.Label;
import com.intellij.history.LocalHistory;
import com.intellij.history.LocalHistoryAction;
import com.intellij.ide.errorTreeView.HotfixData;
import com.intellij.internal.statistic.StructuredIdeActivity;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.HtmlBuilder;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.actions.DescindingFilesFilter;
import com.intellij.openapi.vcs.changes.RemoteRevisionsCache;
import com.intellij.openapi.vcs.changes.VcsAnnotationRefresher;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.changes.committed.CommittedChangesCache;
import com.intellij.openapi.vcs.ex.ProjectLevelVcsManagerEx;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.search.scope.packageSet.NamedScope;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.JBIterable;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.ui.OptionsDialog;
import com.intellij.vcs.VcsActivity;
import com.intellij.vcs.ViewUpdateInfoNotification;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

import static com.intellij.configurationStore.StoreUtilKt.forPoorJavaClientOnlySaveProjectIndEdtDoNotUseThisMethod;
import static com.intellij.openapi.vcs.changes.actions.VcsStatisticsCollector.UPDATE_ACTIVITY;

public abstract class AbstractCommonUpdateAction extends DumbAwareAction {
  private static final Logger LOG = Logger.getInstance(AbstractCommonUpdateAction.class);

  private final boolean myAlwaysVisible;

  private final ActionInfo myActionInfo;
  private final ScopeInfo myScopeInfo;

  protected AbstractCommonUpdateAction(ActionInfo actionInfo, ScopeInfo scopeInfo, boolean alwaysVisible) {
    myActionInfo = actionInfo;
    myScopeInfo = scopeInfo;
    myAlwaysVisible = alwaysVisible;
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    final Project project = e.getProject();
    boolean showUpdateOptions = isShowOptions(project);

    LOG.debug("project: " + project + ", show update options: " + showUpdateOptions);

    if (project == null) {
      return;
    }

    try {
      FilePath[] roots = getRoots(project, e.getDataContext());
      if (roots.length == 0) {
        LOG.debug("No roots found.");
        return;
      }

      final Map<AbstractVcs, Collection<FilePath>> vcsToVirtualFiles = createVcsToFilesMap(roots, project);

      for (AbstractVcs vcs : vcsToVirtualFiles.keySet()) {
        final UpdateEnvironment updateEnvironment = myActionInfo.getEnvironment(vcs);
        if ((updateEnvironment != null) && (!updateEnvironment.validateOptions(vcsToVirtualFiles.get(vcs)))) {
          // messages already shown
          LOG.debug("Options not valid for files: " + vcsToVirtualFiles);
          return;
        }
      }

      if (showUpdateOptions || OptionsDialog.shiftIsPressed(e.getModifiers())) {
        String scopeName = myScopeInfo.getScopeName(e.getDataContext(), myActionInfo);
        showOptionsDialog(vcsToVirtualFiles, project, scopeName);
      }

      if (ApplicationManager.getApplication().isDispatchThread()) {
        // Not only documents, but also project settings should be saved,
        // to ensure that if as a result of Update some project settings will be changed,
        // all local changes are saved in prior and do not overwrite remote changes.
        // Also, there is a chance that save during update can break it -
        // we do disable auto saving during update, but still, there is a chance that save will occur.
        FileDocumentManager.getInstance().saveAllDocuments();
        forPoorJavaClientOnlySaveProjectIndEdtDoNotUseThisMethod(project, false);
      }

      Task.Backgroundable task = new Updater(project, roots, vcsToVirtualFiles, myActionInfo, getTemplatePresentation().getText()) {
        @Override
        public void onSuccess() {
          super.onSuccess();
          AbstractCommonUpdateAction.this.onSuccess();
        }
      };

      if (ApplicationManager.getApplication().isUnitTestMode()) {
        task.run(new EmptyProgressIndicator());
      }
      else {
        ProgressManager.getInstance().run(task);
      }
    }
    catch (ProcessCanceledException ignored) {
    }
  }

  protected boolean isShowOptions(Project project) {
    return myActionInfo.showOptions(project);
  }

  protected void onSuccess() { }

  private static boolean someSessionWasCanceled(List<? extends UpdateSession> updateSessions) {
    for (UpdateSession updateSession : updateSessions) {
      if (updateSession.isCanceled()) {
        return true;
      }
    }
    return false;
  }

  private static @NlsContexts.NotificationContent String getAllFilesAreUpToDateMessage(FilePath[] roots) {
    if (roots.length == 1 && !roots[0].isDirectory()) {
      return VcsBundle.message("message.text.file.is.up.to.date");
    }
    else {
      return VcsBundle.message("message.text.all.files.are.up.to.date");
    }
  }

  private void showOptionsDialog(final Map<AbstractVcs, Collection<FilePath>> updateEnvToVirtualFiles, final Project project,
                                 final String scopeName) {
    LinkedHashMap<Configurable, AbstractVcs> envToConfMap = createConfigurableToEnvMap(updateEnvToVirtualFiles);
    LOG.debug("configurables map: " + envToConfMap);
    if (!envToConfMap.isEmpty()) {
      UpdateOrStatusOptionsDialog dialogOrStatus = myActionInfo.createOptionsDialog(project, envToConfMap, scopeName);
      if (!dialogOrStatus.showAndGet()) {
        throw new ProcessCanceledException();
      }
    }
  }

  private FilePath[] getRoots(Project project, @NotNull DataContext context) {
    List<FilePath> filePaths = myScopeInfo.getRoots(context, myActionInfo);
    return DescindingFilesFilter.filterDescindingFiles(filterRoots(project, filePaths), project);
  }

  private LinkedHashMap<Configurable, AbstractVcs> createConfigurableToEnvMap(Map<AbstractVcs, Collection<FilePath>> updateEnvToVirtualFiles) {
    LinkedHashMap<Configurable, AbstractVcs> envToConfMap = new LinkedHashMap<>();
    for (AbstractVcs vcs : updateEnvToVirtualFiles.keySet()) {
      Configurable configurable = myActionInfo.getEnvironment(vcs).createConfigurable(updateEnvToVirtualFiles.get(vcs));
      if (configurable != null) {
        envToConfMap.put(configurable, vcs);
      }
    }
    return envToConfMap;
  }

  public LinkedHashMap<Configurable, AbstractVcs> getConfigurableToEnvMap(Project project) {
    FilePath[] roots = getRoots(project, SimpleDataContext.getProjectContext(project));
    Map<AbstractVcs, Collection<FilePath>> vcsToFilesMap = createVcsToFilesMap(roots, project);
    return createConfigurableToEnvMap(vcsToFilesMap);
  }

  private Map<AbstractVcs, Collection<FilePath>> createVcsToFilesMap(FilePath @NotNull [] roots, @NotNull Project project) {
    MultiMap<AbstractVcs, FilePath> resultPrep = MultiMap.createSet();
    for (FilePath file : roots) {
      AbstractVcs vcs = VcsUtil.getVcsFor(project, file);
      if (vcs != null) {
        UpdateEnvironment updateEnvironment = myActionInfo.getEnvironment(vcs);
        if (updateEnvironment != null) {
          resultPrep.putValue(vcs, file);
        }
      }
    }

    final Map<AbstractVcs, Collection<FilePath>> result = new HashMap<>();
    for (Map.Entry<AbstractVcs, Collection<FilePath>> entry : resultPrep.entrySet()) {
      AbstractVcs vcs = entry.getKey();
      result.put(vcs, vcs.filterUniqueRoots(new ArrayList<>(entry.getValue()), FilePath::getVirtualFile));
    }
    return result;
  }

  private FilePath @NotNull [] filterRoots(@NotNull Project project, @NotNull List<FilePath> roots) {
    final ArrayList<FilePath> result = new ArrayList<>();
    for (FilePath file : roots) {
      AbstractVcs vcs = VcsUtil.getVcsFor(project, file);
      if (vcs != null) {
        if (!myScopeInfo.filterExistsInVcs() || AbstractVcs.fileInVcsByFileStatus(project, file)) {
          UpdateEnvironment updateEnvironment = myActionInfo.getEnvironment(vcs);
          if (updateEnvironment != null) {
            result.add(file);
          }
        }
        else {
          final VirtualFile virtualFile = file.getVirtualFile();
          if (virtualFile != null && virtualFile.isDirectory()) {
            final VirtualFile[] vcsRoots = ProjectLevelVcsManager.getInstance(project).getAllVersionedRoots();
            for (VirtualFile vcsRoot : vcsRoots) {
              if (VfsUtilCore.isAncestor(virtualFile, vcsRoot, false)) {
                result.add(file);
              }
            }
          }
        }
      }
    }
    return result.toArray(new FilePath[0]);
  }

  protected abstract boolean filterRootsBeforeAction();

  @Override
  public void update(@NotNull AnActionEvent e) {
    Presentation presentation = e.getPresentation();
    Project project = e.getProject();

    if (project == null) {
      presentation.setEnabledAndVisible(false);
      return;
    }

    final ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(project);
    final boolean underVcs = vcsManager.hasActiveVcss();
    if (!underVcs) {
      presentation.setEnabledAndVisible(false);
      return;
    }

    String scopeName = myScopeInfo.getScopeName(e.getDataContext(), myActionInfo);
    String actionName = myActionInfo.getActionName(scopeName);
    if (myActionInfo.showOptions(project) || OptionsDialog.shiftIsPressed(e.getModifiers())) {
      actionName += "...";
    }
    presentation.setText(actionName);

    if (supportingVcsesAreEmpty(vcsManager, myActionInfo)) {
      presentation.setEnabledAndVisible(false);
      return;
    }

    if (filterRootsBeforeAction()) {
      List<FilePath> filePaths = myScopeInfo.getRoots(e.getDataContext(), myActionInfo);
      FilePath[] roots = filterRoots(project, filePaths);
      if (roots.length == 0) {
        presentation.setVisible(myAlwaysVisible);
        presentation.setEnabled(false);
        return;
      }
    }

    final AbstractVcs singleVcs = vcsManager.getSingleVCS();
    presentation.setVisible(true);
    presentation.setEnabled(!vcsManager.isBackgroundVcsOperationRunning() &&
                            (singleVcs == null || !singleVcs.isUpdateActionDisabled()));
  }

  private static boolean supportingVcsesAreEmpty(final ProjectLevelVcsManager vcsManager, final ActionInfo actionInfo) {
    final AbstractVcs[] allActiveVcss = vcsManager.getAllActiveVcss();
    for (AbstractVcs activeVcs : allActiveVcss) {
      if (actionInfo.getEnvironment(activeVcs) != null) return false;
    }
    return true;
  }

  @ApiStatus.Internal
  public static class Updater extends Task.Backgroundable {

    private final ProjectLevelVcsManagerEx myProjectLevelVcsManager;
    protected UpdatedFiles myUpdatedFiles;
    private final FilePath[] myRoots;
    private final Map<AbstractVcs, Collection<FilePath>> myVcsToVirtualFiles;
    private final Map<HotfixData, List<VcsException>> myGroupedExceptions;
    private final List<UpdateSession> myUpdateSessions;
    private int myUpdateNumber;

    // vcs name, context object
    private final Map<AbstractVcs, SequentialUpdatesContext> myContextInfo;
    private final VcsDirtyScopeManager myDirtyScopeManager;

    private Label myBefore;
    private Label myAfter;
    private LocalHistoryAction myLocalHistoryAction;

    private final ActionInfo myActionInfo;
    private final @Nls String myActionName;

    public Updater(@NotNull Project project,
                   final FilePath[] roots,
                   final Map<AbstractVcs, Collection<FilePath>> vcsToVirtualFiles,
                   final ActionInfo actionInfo,
                   final @NlsContexts.ProgressTitle String actionName) {
      super(project, actionName, true);
      myProjectLevelVcsManager = ProjectLevelVcsManagerEx.getInstanceEx(project);
      myDirtyScopeManager = VcsDirtyScopeManager.getInstance(myProject);
      myRoots = roots;
      myVcsToVirtualFiles = vcsToVirtualFiles;

      myUpdatedFiles = UpdatedFiles.create();
      myGroupedExceptions = new HashMap<>();
      myUpdateSessions = new ArrayList<>();

      myActionInfo = actionInfo;
      myActionName = actionName;

      // create from outside without any context; context is created by vcses
      myContextInfo = new HashMap<>();
      myUpdateNumber = 1;
    }

    private void reset() {
      myUpdatedFiles = UpdatedFiles.create();
      myGroupedExceptions.clear();
      myUpdateSessions.clear();
      ++myUpdateNumber;
    }

    @Override
    public void run(final @NotNull ProgressIndicator indicator) {
      runImpl();
    }

    private void runImpl() {
      if (myProject != null) {
        StoreReloadManager.Companion.getInstance(myProject).blockReloadingProjectOnExternalChanges();
      }
      myProjectLevelVcsManager.startBackgroundVcsOperation();

      myBefore = LocalHistory.getInstance().putSystemLabel(myProject, VcsBundle.message("update.label.before.update"));
      myLocalHistoryAction = LocalHistory.getInstance().startAction(VcsBundle.message("activity.name.update"), VcsActivity.Update);
      ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
      if (progressIndicator != null) {
        progressIndicator.setIndeterminate(false);
      }
      StructuredIdeActivity activity = UPDATE_ACTIVITY.started(myProject);
      try {
        int toBeProcessed = myVcsToVirtualFiles.size();
        int processed = 0;
        for (AbstractVcs vcs : myVcsToVirtualFiles.keySet()) {
          final UpdateEnvironment updateEnvironment = myActionInfo.getEnvironment(vcs);
          updateEnvironment.fillGroups(myUpdatedFiles);
          Collection<FilePath> files = myVcsToVirtualFiles.get(vcs);

          final SequentialUpdatesContext context = myContextInfo.get(vcs);
          final Ref<SequentialUpdatesContext> refContext = new Ref<>(context);

          // actual update
          UpdateSession updateSession = performUpdate(progressIndicator, updateEnvironment, files, refContext);

          myContextInfo.put(vcs, refContext.get());
          processed++;
          if (progressIndicator != null) {
            progressIndicator.setFraction((double)processed / (double)toBeProcessed);
            progressIndicator.setText2("");
          }
          final List<VcsException> exceptionList = updateSession.getExceptions();
          gatherExceptions(vcs, exceptionList);
          myUpdateSessions.add(updateSession);
        }
      }
      finally {
        try {
          ProgressManager.progress(VcsBundle.message("progress.text.synchronizing.files"));
          doVfsRefresh();
        }
        finally {
          myProjectLevelVcsManager.stopBackgroundVcsOperation();
          myProject.getMessageBus().syncPublisher(UpdatedFilesListener.UPDATED_FILES).
            consume(UpdatedFilesReverseSide.getPathsFromUpdatedFiles(myUpdatedFiles));
          activity.finished();
        }
      }
    }

    protected @NotNull UpdateSession performUpdate(ProgressIndicator progressIndicator,
                                                   UpdateEnvironment updateEnvironment,
                                                   Collection<FilePath> files, Ref<SequentialUpdatesContext> refContext) {
      return updateEnvironment.updateDirectories(files.toArray(new FilePath[0]), myUpdatedFiles, progressIndicator, refContext);
    }

    private void gatherExceptions(final AbstractVcs vcs, final List<VcsException> exceptionList) {
      final VcsExceptionsHotFixer fixer = vcs.getVcsExceptionsHotFixer();
      if (fixer == null) {
        putExceptions(null, exceptionList);
      }
      else {
        putExceptions(fixer.groupExceptions(ActionType.update, exceptionList));
      }
    }

    private void putExceptions(final Map<HotfixData, List<VcsException>> map) {
      for (Map.Entry<HotfixData, List<VcsException>> entry : map.entrySet()) {
        putExceptions(entry.getKey(), entry.getValue());
      }
    }

    private void putExceptions(final HotfixData key, final @NotNull List<? extends VcsException> list) {
      if (list.isEmpty()) return;
      myGroupedExceptions.computeIfAbsent(key, k -> new ArrayList<>()).addAll(list);
    }

    private void doVfsRefresh() {
      LOG.info("Calling refresh files after update for roots: " + Arrays.toString(myRoots));
      RefreshVFsSynchronously.updateAllChanged(myUpdatedFiles);
      notifyAnnotations();
    }

    private void notifyAnnotations() {
      final VcsAnnotationRefresher refresher = myProject.getMessageBus().syncPublisher(VcsAnnotationRefresher.LOCAL_CHANGES_CHANGED);
      UpdateFilesHelper.iterateFileGroupFilesDeletedOnServerFirst(myUpdatedFiles, new UpdateFilesHelper.Callback() {
        @Override
        public void onFile(String filePath, String groupId) {
          refresher.dirty(filePath);
        }
      });
    }

    private @NotNull Notification prepareNotification(@NotNull UpdateInfoTree tree,
                                                      boolean someSessionWasCancelled,
                                                      @NotNull List<? extends UpdateSession> updateSessions) {
      int allFilesCount = getUpdatedFilesCount();

      String title = someSessionWasCancelled
                     ? VcsBundle.message("update.notification.title.project.partially.updated")
                     : VcsBundle.message("update.notification.title.count.files.updated", allFilesCount);

      HtmlBuilder content = new HtmlBuilder();
      content.append(someSessionWasCancelled
                     ? HtmlChunk.text(VcsBundle.message("update.notification.content.files.updated", allFilesCount))
                     : prepareScopeUpdatedText(tree));

      List<HtmlChunk> additionalContent = JBIterable.from(updateSessions)
        .map(UpdateSession::getAdditionalNotificationContent)
        .filterNotNull()
        .map(HtmlChunk::raw)
        .toList();
      if (!additionalContent.isEmpty()) {
        if (!content.isEmpty()) {
          content.append(HtmlChunk.br());
        }
        content.appendWithSeparators(HtmlChunk.text(", "), additionalContent);
      }


      NotificationType type = someSessionWasCancelled
                              ? NotificationType.WARNING
                              : NotificationType.INFORMATION;


      return VcsNotifier.standardNotification()
        .createNotification(title, content.toString(), type).setDisplayId(VcsNotificationIdsHolder.PROJECT_PARTIALLY_UPDATED);
    }

    private int getUpdatedFilesCount() {
      return myUpdatedFiles.getTopLevelGroups().stream().mapToInt(Updater::getFilesCount).sum();
    }

    private static int getFilesCount(@NotNull FileGroup group) {
      return group.getFiles().size() + group.getChildren().stream().mapToInt(g -> getFilesCount(g)).sum();
    }

    private static @NotNull @Nls HtmlChunk prepareScopeUpdatedText(@NotNull UpdateInfoTree tree) {
      NamedScope scopeFilter = tree.getFilterScope();
      if (scopeFilter != null) {
        int filteredFiles = tree.getFilteredFilesCount();
        String filterName = scopeFilter.getPresentableName();
        if (filteredFiles == 0) {
          return HtmlChunk.text(VcsBundle.message("update.file.name.wasn.t.modified", filterName));
        }
        else {
          return HtmlChunk.text(VcsBundle.message("update.filtered.files.count.in.filter.name", filteredFiles, filterName));
        }
      }
      return HtmlChunk.empty();
    }

    @Override
    public void onSuccess() {
      onSuccessImpl(false);
    }

    private void onSuccessImpl(final boolean wasCanceled) {
      if (!myProject.isOpen() || myProject.isDisposed()) {
        LocalHistory.getInstance().putSystemLabel(myProject, VcsBundle.message("activity.name.update")); // TODO check why this label is needed
        return;
      }
      boolean continueChain = false;
      for (SequentialUpdatesContext context : myContextInfo.values()) {
        continueChain |= (context != null) && (context.shouldFail());
      }
      final boolean continueChainFinal = continueChain;

      final boolean someSessionWasCancelled = wasCanceled || someSessionWasCanceled(myUpdateSessions);
      // here text conflicts might be interactively resolved
      for (final UpdateSession updateSession : myUpdateSessions) {
        updateSession.onRefreshFilesCompleted();
      }
      // only after conflicts are resolved, put a label
      if (myLocalHistoryAction != null) {
        myLocalHistoryAction.finish();
      }
      myAfter = LocalHistory.getInstance().putSystemLabel(myProject, VcsBundle.message("update.label.after.update"));

      if (myActionInfo.canChangeFileStatus()) {
        final List<VirtualFile> files = new ArrayList<>();
        final RemoteRevisionsCache revisionsCache = RemoteRevisionsCache.getInstance(myProject);
        revisionsCache.invalidate(myUpdatedFiles);
        UpdateFilesHelper.iterateFileGroupFiles(myUpdatedFiles, new UpdateFilesHelper.Callback() {
          @Override
          public void onFile(final String filePath, final String groupId) {
            final @NonNls String path = VfsUtilCore.pathToUrl(filePath.replace(File.separatorChar, '/'));
            final VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(path);
            if (file != null) {
              files.add(file);
            }
          }
        });
        myDirtyScopeManager.filesDirty(files, null);
      }

      final boolean updateSuccess = !someSessionWasCancelled && myGroupedExceptions.isEmpty();

      if (myProject.isDisposed()) {
        StoreReloadManager.Companion.getInstance(myProject).unblockReloadingProjectOnExternalChanges();
        return;
      }

      if (!myGroupedExceptions.isEmpty()) {
        if (continueChainFinal) {
          gatherContextInterruptedMessages();
        }
        AbstractVcsHelper.getInstance(myProject).showErrors(myGroupedExceptions, VcsBundle.message("message.title.vcs.update.errors",
                                                                                                   myActionName));
      }
      else if (someSessionWasCancelled) {
        ProgressManager.progress(VcsBundle.message("progress.text.updating.canceled"));
      }
      else {
        ProgressManager.progress(VcsBundle.message("progress.text.updating.done"));
      }

      final boolean noMerged = myUpdatedFiles.getGroupById(FileGroup.MERGED_WITH_CONFLICT_ID).isEmpty();
      if (myUpdatedFiles.isEmpty() && myGroupedExceptions.isEmpty()) {
        NotificationType type;
        String content;
        if (someSessionWasCancelled) {
          content = VcsBundle.message("progress.text.updating.canceled");
          type = NotificationType.WARNING;
        }
        else {
          content = getAllFilesAreUpToDateMessage(myRoots);
          type = NotificationType.INFORMATION;
        }
        VcsNotifier.getInstance(myProject).notify(
          VcsNotifier.standardNotification().createNotification(content, type)
            .setDisplayId(VcsNotificationIdsHolder.PROJECT_UPDATE_FINISHED));
      }
      else if (!myUpdatedFiles.isEmpty()) {

        if (myUpdateSessions.size() == 1 && showsCustomNotification(myVcsToVirtualFiles.keySet())) {
          // multi-vcs projects behave as before: only a compound notification & file tree is shown for them, for the sake of simplicity
          myUpdateSessions.get(0).showNotification();
        }
        else {
          final UpdateInfoTree tree = showUpdateTree(continueChainFinal && updateSuccess && noMerged, someSessionWasCancelled);
          final CommittedChangesCache cache = CommittedChangesCache.getInstance(myProject);
          cache.processUpdatedFiles(myUpdatedFiles, incomingChangeLists -> tree.setChangeLists(incomingChangeLists));

          Notification notification = prepareNotification(tree, someSessionWasCancelled, myUpdateSessions);
          notification.addAction(new ViewUpdateInfoNotification(myProject, tree, VcsBundle.message("update.notification.content.view"), notification));
          VcsNotifier.getInstance(myProject).notify(notification);
        }
      }


      StoreReloadManager.Companion.getInstance(myProject).unblockReloadingProjectOnExternalChanges();

      if (continueChainFinal && updateSuccess) {
        if (!noMerged) {
          showContextInterruptedError();
        }
        else {
          // trigger next update; for CVS when updating from several branches simultaneously
          reset();
          ProgressManager.getInstance().run(this);
        }
      }
    }

    private void showContextInterruptedError() {
      gatherContextInterruptedMessages();
      AbstractVcsHelper.getInstance(myProject).showErrors(myGroupedExceptions,
                                                          VcsBundle.message("message.title.vcs.update.errors", myActionName));
    }

    private void gatherContextInterruptedMessages() {
      for (Map.Entry<AbstractVcs, SequentialUpdatesContext> entry : myContextInfo.entrySet()) {
        final SequentialUpdatesContext context = entry.getValue();
        if ((context == null) || (!context.shouldFail())) continue;
        final VcsException exception = new VcsException(context.getMessageWhenInterruptedBeforeStart());
        gatherExceptions(entry.getKey(), Collections.singletonList(exception));
      }
    }

    private @NotNull UpdateInfoTree showUpdateTree(final boolean willBeContinued, final boolean wasCanceled) {
      RestoreUpdateTree restoreUpdateTree = RestoreUpdateTree.getInstance(myProject);
      restoreUpdateTree.registerUpdateInformation(myUpdatedFiles, myActionInfo);
      final String text = myActionName + ((willBeContinued || (myUpdateNumber > 1)) ? ("#" + myUpdateNumber) : "");
      UpdateInfoTree updateInfoTree =
        Objects.requireNonNull(myProjectLevelVcsManager.showUpdateProjectInfo(myUpdatedFiles, text, myActionInfo,
                                                                              wasCanceled));
      updateInfoTree.setBefore(myBefore);
      updateInfoTree.setAfter(myAfter);
      updateInfoTree.setCanGroupByChangeList(canGroupByChangelist(myVcsToVirtualFiles.keySet()));
      return updateInfoTree;
    }

    private boolean canGroupByChangelist(final Set<? extends AbstractVcs> abstractVcses) {
      if (myActionInfo.canGroupByChangelist()) {
        for (AbstractVcs vcs : abstractVcses) {
          if (vcs.getCachingCommittedChangesProvider() != null) {
            return true;
          }
        }
      }
      return false;
    }

    @Override
    public void onCancel() {
      onSuccessImpl(true);
    }
  }

  public static boolean showsCustomNotification(@NotNull Collection<? extends AbstractVcs> vcss) {
    return ContainerUtil.all(vcss, vcs -> {
      UpdateEnvironment environment = vcs.getUpdateEnvironment();
      return environment != null && environment.hasCustomNotification();
    });
  }
}
