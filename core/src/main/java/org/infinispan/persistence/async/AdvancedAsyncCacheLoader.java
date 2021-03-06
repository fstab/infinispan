package org.infinispan.persistence.async;

import org.infinispan.persistence.PersistenceUtil;
import org.infinispan.persistence.modifications.Modification;
import org.infinispan.persistence.modifications.Remove;
import org.infinispan.persistence.modifications.Store;
import org.infinispan.persistence.spi.AdvancedCacheLoader;
import org.infinispan.persistence.spi.CacheLoader;
import org.infinispan.marshall.core.MarshalledEntry;
import org.infinispan.persistence.TaskContextImpl;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Mircea Markus
 * @since 6.0
 */
public class AdvancedAsyncCacheLoader extends AsyncCacheLoader implements AdvancedCacheLoader {

   public AdvancedAsyncCacheLoader(CacheLoader actual, AtomicReference<State> state) {
      super(actual, state);
   }


   private void loadAllKeys(State s, final Set<Object> result, final KeyFilter filter, final Executor executor) {
      // if not cleared, get keys from next State or the back-end store
      if (!s.clear) {
         State next = s.next;
         if (next != null) {
            loadAllKeys(next, result, filter, executor);
         } else {
            advancedLoader().process(filter, new CacheLoaderTask() {
               @Override
               public void processEntry(MarshalledEntry marshalledEntry, TaskContext taskContext) throws InterruptedException {
                  result.add(marshalledEntry.getKey());
               }
            }, executor, false, false);
         }
      }

      // merge keys of the current State
      for (Modification mod : s.modifications.values()) {
         switch (mod.getType()) {
            case STORE:
               Object key = ((Store) mod).getStoredValue().getKey();
               if (filter == null || filter.shouldLoadKey(key))
                  result.add(key);
               break;
            case REMOVE:
               result.remove(((Remove) mod).getKey());
               break;
         }
      }
   }


   @SuppressWarnings("unchecked")
   @Override
   public void process(KeyFilter keyFilter, CacheLoaderTask cacheLoaderTask, Executor executor, boolean loadValues, boolean loadMetadata) {

      int batchSize = 100;
      ExecutorCompletionService ecs = new ExecutorCompletionService(executor);
      int tasks = 0;
      final TaskContext taskContext = new TaskContextImpl();

      Set<Object> allKeys = new HashSet<Object>(batchSize);
      Set<Object> batch = new HashSet<Object>();
      loadAllKeys(state.get(), allKeys, keyFilter, executor);
      for (Iterator it = allKeys.iterator(); it.hasNext(); ) {
         batch.add(it.next());
         if (batch.size() == batchSize) {
            final Set<Object> toHandle = batch;
            batch = new HashSet<Object>(batchSize);
            submitProcessTask(cacheLoaderTask, ecs, taskContext, toHandle);
            tasks++;
         }
      }
      if (!batch.isEmpty()) {
         submitProcessTask(cacheLoaderTask, ecs, taskContext, batch);
         tasks++;
      }

      PersistenceUtil.waitForAllTasksToComplete(ecs, tasks);

   }

   private void submitProcessTask(final CacheLoaderTask cacheLoaderTask, ExecutorCompletionService ecs, final TaskContext taskContext, final Set<Object> batch) {
      ecs.submit(new Callable() {
         @Override
         public Object call() throws Exception {
            for (Object k : batch) {
               if (taskContext.isStopped())
                  break;
               MarshalledEntry load = load(k);
               if (load != null)
                  cacheLoaderTask.processEntry(load, taskContext);
            }
            return null;
         }
      });
   }

   @Override
   public int size() {
      //an estimate value anyway
      return advancedLoader().size();
   }

   AdvancedCacheLoader advancedLoader() {
      return (AdvancedCacheLoader) actual;
   }
}
