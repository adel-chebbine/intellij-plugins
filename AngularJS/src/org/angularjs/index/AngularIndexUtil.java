package org.angularjs.index;

import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding;
import com.intellij.lang.javascript.DialectDetector;
import com.intellij.lang.javascript.psi.*;
import com.intellij.lang.javascript.psi.impl.JSOffsetBasedImplicitElement;
import com.intellij.lang.javascript.psi.resolve.JSResolveResult;
import com.intellij.lang.javascript.psi.stubs.JSElementIndexingData;
import com.intellij.lang.javascript.psi.stubs.JSImplicitElement;
import com.intellij.lang.javascript.psi.stubs.impl.JSImplicitElementImpl;
import com.intellij.lang.javascript.psi.util.JSStubBasedPsiTreeUtil;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.NoAccessDuringPsiEvents;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.stubs.StubIndexKey;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider.Result;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.ParameterizedCachedValue;
import com.intellij.psi.util.ParameterizedCachedValueProvider;
import com.intellij.util.CachedValueBase;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.Function;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.ID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * @author Dennis.Ushakov
 */
public class AngularIndexUtil {
  public static final int BASE_VERSION = 65;
  public static final Function<JSImplicitElement, ResolveResult> JS_IMPLICIT_TO_RESOLVE_RESULT = JSResolveResult::new;

  private static final ConcurrentMap<String, Key<ParameterizedCachedValue<Collection<String>, Pair<Project, ID<String, ?>>>>> ourCacheKeys =
    ContainerUtil.newConcurrentMap();
  private static final AngularKeysProvider PROVIDER = new AngularKeysProvider();
  private static final Key<Future<Integer>> GET_VERSION_FUTURE_KEY = new Key<>("angularjs.get.version.future");
  private static final Key<CachedValue<Integer>> ANGULARJS_VERSION_KEY = new Key<>("angularjs.version");

  @Nullable
  public static JSImplicitElement resolve(@NotNull Project project,
                                          @NotNull StubIndexKey<? super String, JSImplicitElementProvider> index,
                                          @NotNull String lookupKey) {
    Ref<JSImplicitElement> result = new Ref<>(null);
    Processor<JSImplicitElement> processor = element -> {
      result.set(element);
      if (DialectDetector.isTypeScript(element)) {
        return false;
      }
      return true;
    };
    multiResolve(project, GlobalSearchScope.allScope(project), index, lookupKey, processor);

    return result.get();
  }

  public static void multiResolve(@NotNull Project project,
                                  @NotNull StubIndexKey<? super String, JSImplicitElementProvider> index,
                                  @NotNull String lookupKey,
                                  @NotNull Processor<? super JSImplicitElement> processor) {
    multiResolve(project, GlobalSearchScope.allScope(project), index, lookupKey, processor);
  }

  public static void multiResolve(@NotNull Project project,
                                  @NotNull GlobalSearchScope scope,
                                  @NotNull StubIndexKey<? super String, JSImplicitElementProvider> index,
                                  @NotNull String lookupKey,
                                  @NotNull Processor<? super JSImplicitElement> processor) {
    StubIndex.getInstance().processElements(
      index, lookupKey, project, scope, JSImplicitElementProvider.class, provider -> {
        final JSElementIndexingData indexingData = provider.getIndexingData();
        if (indexingData != null) {
          final Collection<JSImplicitElement> elements = indexingData.getImplicitElements();
          if (elements != null) {
            for (JSImplicitElement element : elements) {
              if (element.getQualifiedName().equals(lookupKey)
                  && ((index != AngularDirectivesIndex.KEY && index != AngularDirectivesDocIndex.KEY) ||
                      (!element.getType().isFunction()
                       && AngularJSIndexingHandler.isAngularRestrictions(element.getTypeString())))) {
                if (!processor.process(element)) return false;
              }
            }
          }
        }
        return true;
      }
    );
  }

  @NotNull
  public static ResolveResult[] multiResolveAngularNamedDefinitionIndex(@NotNull final Project project,
                                                                        @NotNull final ID<? super String, AngularNamedItemDefinition> INDEX,
                                                                        @NotNull final String id,
                                                                        @NotNull final Condition<? super VirtualFile> filter,
                                                                        boolean dirtyResolve) {
    final FileBasedIndex instance = FileBasedIndex.getInstance();
    Collection<VirtualFile> files = instance.getContainingFiles(INDEX, id, GlobalSearchScope.allScope(project));
    if (files.isEmpty()) return ResolveResult.EMPTY_ARRAY;
    final List<VirtualFile> filtered = ContainerUtil.filter(files, filter);
    if (filtered.isEmpty()) {
      if (!dirtyResolve) return ResolveResult.EMPTY_ARRAY;
    }
    else {
      files = filtered;
    }

    final List<JSImplicitElement> elements = new ArrayList<>();
    for (VirtualFile file : files) {
      final List<AngularNamedItemDefinition> values = instance.getValues(INDEX, id, GlobalSearchScope.fileScope(project, file));
      for (AngularNamedItemDefinition value : values) {
        JSQualifiedNameImpl qName = JSQualifiedNameImpl.fromQualifiedName(id);
        JSImplicitElementImpl.Builder elementBuilder = new JSImplicitElementImpl.Builder(qName, null);
        final PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile != null) {
          elements.add(new JSOffsetBasedImplicitElement(elementBuilder, (int)value.getStartOffset(), psiFile));
        }
      }
    }
    final List<ResolveResult> list = ContainerUtil.map(elements, JS_IMPLICIT_TO_RESOLVE_RESULT);
    return list.toArray(ResolveResult.EMPTY_ARRAY);
  }

  @NotNull
  public static Collection<String> getAllKeys(@NotNull final ID<String, ?> index, @NotNull final Project project) {
    final String indexId = index.getName();
    final Key<ParameterizedCachedValue<Collection<String>, Pair<Project, ID<String, ?>>>> key =
      ConcurrencyUtil.cacheOrGet(ourCacheKeys, indexId, Key.create("angularjs.index." + indexId));
    final Pair<Project, ID<String, ?>> pair = Pair.create(project, index);
    return CachedValuesManager.getManager(project).getParameterizedCachedValue(project, key, PROVIDER, false, pair);
  }

  public static boolean hasAngularJS(@NotNull final Project project) {
    if (ApplicationManager.getApplication().isUnitTestMode() && "disabled".equals(System.getProperty("angular.js"))) return false;
    return getAngularJSVersion(project) > 0;
  }

  private static int getAngularJSVersion(@NotNull final Project project) {
    if (DumbService.isDumb(project) || NoAccessDuringPsiEvents.isInsideEventProcessing()) return -1;
    Application app = ApplicationManager.getApplication();
    if (app.isDispatchThread() && !app.isUnitTestMode()) {
      return getAngularJSVersionOnEDT(project);
    }
    return getCachedAngularJSVersion(project);
  }

  private static int getCachedAngularJSVersion(@NotNull final Project project) {
    return CachedValuesManager.getManager(project).getCachedValue(project, ANGULARJS_VERSION_KEY, () -> {
      int version = -1;
      PsiElement resolve;
      if ((resolve = resolve(project, AngularDirectivesIndex.KEY, "ngMessages")) != null) {
        version = 13;
      }
      else if ((resolve = resolve(project, AngularDirectivesIndex.KEY, "ngModel")) != null) {
        version = 12;
      }
      if (resolve != null) {
        return Result.create(version, resolve.getContainingFile());
      }
      return Result.create(version, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                           ProjectRootModificationTracker.getInstance(project));
    }, false);
  }

  /**
   * Special handling on EDT to avoid freeze - this isn't a perfect solution and might result
   * in instability of some editor features running on EDT, on the other hand changing logic to
   * depend on package.json and node_modules content as is case with Angular might cause regressions
   * in support for older projects.
   */
  private static int getAngularJSVersionOnEDT(@NotNull final Project project) {
    // Short path to avoid delegating to other thread if there is up-to-date cached value
    CachedValue<Integer> value = project.getUserData(ANGULARJS_VERSION_KEY);
    if (value instanceof CachedValueBase && ((CachedValueBase<?>)value).isFromMyProject(project)) {
      Getter<Integer> data = value.getUpToDateOrNull();
      if (data != null) {
        return data.get();
      }
    }
    Future<Integer> future = project.getUserData(GET_VERSION_FUTURE_KEY);

    // If previous calculation isn't done, don't even try to run new one
    if (future == null || future.isDone()) {
      future = ApplicationManager.getApplication().executeOnPooledThread(() -> ReadAction.compute(
        () -> project.isInitialized() ? getCachedAngularJSVersion(project) : -1
      ));
      project.putUserData(GET_VERSION_FUTURE_KEY, future);
      try {
        Integer result = future.get(50, TimeUnit.MILLISECONDS);
        project.putUserData(GET_VERSION_FUTURE_KEY, null);
        return result;
      }
      catch (TimeoutException | ExecutionException | InterruptedException ignored) {
      }
    }
    return -1;
  }

  public static boolean hasFileReference(@NotNull PsiElement element, @NotNull PsiFile file) {
    VirtualFile vf = file.getOriginalFile().getViewProvider().getVirtualFile();
    if (element instanceof JSCallExpression) {
      JSExpression[] args = ((JSCallExpression)element).getArguments();
      if (args.length == 1 && args[0] instanceof JSLiteralExpression) {
        element = args[0];
      }
      else {
        return false;
      }
    }
    for (PsiReference ref : element.getReferences()) {
      PsiElement resolvedElement = ref.resolve();
      PsiFile resolvedFile = null;
      if (resolvedElement instanceof PsiFile) {
        resolvedFile = (PsiFile)resolvedElement;
      }
      else if (resolvedElement instanceof ES6ImportedBinding) {
        for (PsiElement importedElement : ((ES6ImportedBinding)resolvedElement).findReferencedElements()) {
          if (importedElement instanceof PsiFile) {
            resolvedFile = (PsiFile)importedElement;
            break;
          }
        }
      }
      if (resolvedFile != null
          && resolvedFile.getOriginalFile().getViewProvider().getVirtualFile().equals(vf)) {
        return true;
      }
    }
    return false;
  }

  @NotNull
  public static List<PsiElement> resolveLocally(@NotNull JSReferenceExpression ref) {
    if (ref.getQualifier() == null && ref.getReferenceName() != null) {
      return JSStubBasedPsiTreeUtil.resolveLocallyWithMergedResults(ref.getReferenceName(), ref);
    }
    return Collections.emptyList();
  }

  @NotNull
  public static String convertRestrictions(@NotNull final Project project, @NotNull String restrictions) {
    if (AngularJSIndexingHandler.DEFAULT_RESTRICTIONS.equals(restrictions)) {
      return getAngularJSVersion(project) >= 13 ? "E" : "_";
    }
    return restrictions;
  }

  private static class AngularKeysProvider implements ParameterizedCachedValueProvider<Collection<String>, Pair<Project, ID<String, ?>>> {
    @SuppressWarnings("unchecked")
    @Override
    public Result<Collection<String>> compute(final Pair<Project, ID<String, ?>> projectAndIndex) {
      final Project project = projectAndIndex.first;
      final ID<String, ?> id = projectAndIndex.second;
      final GlobalSearchScope scope = GlobalSearchScope.allScope(project);
      final FileBasedIndex fileIndex = FileBasedIndex.getInstance();
      final StubIndex stubIndex = StubIndex.getInstance();
      Collection<String> allKeys = id instanceof StubIndexKey
                                   ? stubIndex.getAllKeys((StubIndexKey<String, ?>)id, project)
                                   : fileIndex.getAllKeys(id, project);

      List<String> filteredKeys = ContainerUtil.filter(
        allKeys,
        key -> id instanceof StubIndexKey
               ? !stubIndex.processElements((StubIndexKey<String, PsiElement>)id, key, project, scope, PsiElement.class, element -> false)
               : !fileIndex.processValues(id, key, null, (file, value) -> false, scope)
      );
      return Result.create(filteredKeys, PsiManager.getInstance(project).getModificationTracker());
    }
  }
}
