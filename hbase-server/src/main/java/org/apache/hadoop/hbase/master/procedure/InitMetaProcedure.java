/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master.procedure;

import static org.apache.hadoop.hbase.NamespaceDescriptor.DEFAULT_NAMESPACE;
import static org.apache.hadoop.hbase.NamespaceDescriptor.SYSTEM_NAMESPACE;
import static org.apache.hadoop.hbase.master.TableNamespaceManager.insertNamespaceToMeta;
import static org.apache.hadoop.hbase.master.procedure.AbstractStateMachineNamespaceProcedure.createDirectory;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.RegionInfoBuilder;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.master.assignment.TransitRegionStateProcedure;
import org.apache.hadoop.hbase.procedure2.ProcedureStateSerializer;
import org.apache.hadoop.hbase.procedure2.ProcedureSuspendedException;
import org.apache.hadoop.hbase.procedure2.ProcedureUtil;
import org.apache.hadoop.hbase.procedure2.ProcedureYieldException;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.StoreFileInfo;
import org.apache.hadoop.hbase.util.CommonFSUtils;
import org.apache.hadoop.hbase.util.FSTableDescriptors;
import org.apache.hadoop.hbase.util.RetryCounter;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProcedureProtos.InitMetaState;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProcedureProtos.InitMetaStateData;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ProcedureProtos;

/**
 * This procedure is used to initialize meta table for a new hbase deploy. It will just schedule an
 * {@link TransitRegionStateProcedure} to assign meta.
 */
@InterfaceAudience.Private
public class InitMetaProcedure extends AbstractStateMachineTableProcedure<InitMetaState> {

  private static final Logger LOG = LoggerFactory.getLogger(InitMetaProcedure.class);

  private CountDownLatch latch = new CountDownLatch(1);

  private RetryCounter retryCounter;

  @Override
  public TableName getTableName() {
    return TableName.META_TABLE_NAME;
  }

  @Override
  public TableOperationType getTableOperationType() {
    return TableOperationType.CREATE;
  }

  private static TableDescriptor writeFsLayout(Path rootDir, Configuration conf)
    throws IOException {
    LOG.info("BOOTSTRAP: creating hbase:meta region");
    FileSystem fs = rootDir.getFileSystem(conf);
    Path tableDir = CommonFSUtils.getTableDir(rootDir, TableName.META_TABLE_NAME);
    if (fs.exists(tableDir) && !deleteMetaTableDirectoryIfPartial(fs, tableDir)) {
      LOG.warn("Can not delete partial created meta table, continue...");
    }
    // Bootstrapping, make sure blockcache is off. Else, one will be
    // created here in bootstrap and it'll need to be cleaned up. Better to
    // not make it in first place. Turn off block caching for bootstrap.
    // Enable after.
    TableDescriptor metaDescriptor =
      FSTableDescriptors.tryUpdateAndGetMetaTableDescriptor(conf, fs, rootDir);
    HRegion
      .createHRegion(RegionInfoBuilder.FIRST_META_REGIONINFO, rootDir, conf, metaDescriptor, null)
      .close();
    return metaDescriptor;
  }

  @Override
  protected Flow executeFromState(MasterProcedureEnv env, InitMetaState state)
    throws ProcedureSuspendedException, ProcedureYieldException, InterruptedException {
    LOG.debug("Execute {}", this);
    try {
      switch (state) {
        case INIT_META_WRITE_FS_LAYOUT:
          Configuration conf = env.getMasterConfiguration();
          Path rootDir = CommonFSUtils.getRootDir(conf);
          TableDescriptor td = writeFsLayout(rootDir, conf);
          env.getMasterServices().getTableDescriptors().update(td, true);
          setNextState(InitMetaState.INIT_META_ASSIGN_META);
          return Flow.HAS_MORE_STATE;
        case INIT_META_ASSIGN_META:
          LOG.info("Going to assign meta");
          addChildProcedure(env.getAssignmentManager()
            .createAssignProcedures(Arrays.asList(RegionInfoBuilder.FIRST_META_REGIONINFO)));
          setNextState(InitMetaState.INIT_META_CREATE_NAMESPACES);
          return Flow.HAS_MORE_STATE;
        case INIT_META_CREATE_NAMESPACES:
          LOG.info("Going to create {} and {} namespaces", DEFAULT_NAMESPACE, SYSTEM_NAMESPACE);
          createDirectory(env, DEFAULT_NAMESPACE);
          createDirectory(env, SYSTEM_NAMESPACE);
          // here the TableNamespaceManager has not been initialized yet, so we have to insert the
          // record directly into meta table, later the TableNamespaceManager will load these two
          // namespaces when starting.
          insertNamespaceToMeta(env.getMasterServices().getConnection(), DEFAULT_NAMESPACE);
          insertNamespaceToMeta(env.getMasterServices().getConnection(), SYSTEM_NAMESPACE);

          return Flow.NO_MORE_STATE;
        default:
          throw new UnsupportedOperationException("unhandled state=" + state);
      }
    } catch (IOException e) {
      if (retryCounter == null) {
        retryCounter = ProcedureUtil.createRetryCounter(env.getMasterConfiguration());
      }
      long backoff = retryCounter.getBackoffTimeAndIncrementAttempts();
      LOG.warn("Failed to init meta, suspend {}secs", backoff, e);
      setTimeout(Math.toIntExact(backoff));
      setState(ProcedureProtos.ProcedureState.WAITING_TIMEOUT);
      skipPersistence();
      throw new ProcedureSuspendedException();
    }
  }

  @Override
  protected boolean waitInitialized(MasterProcedureEnv env) {
    // we do not need to wait for master initialized, we are part of the initialization.
    return false;
  }

  @Override
  protected synchronized boolean setTimeoutFailure(MasterProcedureEnv env) {
    setState(ProcedureProtos.ProcedureState.RUNNABLE);
    env.getProcedureScheduler().addFront(this);
    return false;
  }

  @Override
  protected void rollbackState(MasterProcedureEnv env, InitMetaState state)
    throws IOException, InterruptedException {
    throw new UnsupportedOperationException();
  }

  @Override
  protected InitMetaState getState(int stateId) {
    return InitMetaState.forNumber(stateId);
  }

  @Override
  protected int getStateId(InitMetaState state) {
    return state.getNumber();
  }

  @Override
  protected InitMetaState getInitialState() {
    return InitMetaState.INIT_META_WRITE_FS_LAYOUT;
  }

  @Override
  protected void serializeStateData(ProcedureStateSerializer serializer) throws IOException {
    super.serializeStateData(serializer);
    serializer.serialize(InitMetaStateData.getDefaultInstance());
  }

  @Override
  protected void deserializeStateData(ProcedureStateSerializer serializer) throws IOException {
    super.deserializeStateData(serializer);
    serializer.deserialize(InitMetaStateData.class);
  }

  @Override
  protected void completionCleanup(MasterProcedureEnv env) {
    latch.countDown();
  }

  public void await() throws InterruptedException {
    latch.await();
  }

  private static boolean deleteMetaTableDirectoryIfPartial(FileSystem rootDirectoryFs,
    Path metaTableDir) throws IOException {
    boolean isPartial = true;
    try {
      TableDescriptor metaDescriptor =
        FSTableDescriptors.getTableDescriptorFromFs(rootDirectoryFs, metaTableDir);
      // when entering the state of INIT_META_WRITE_FS_LAYOUT, if a meta table directory is found,
      // the meta table should not have any useful data and considers as partial.
      // if we find any valid HFiles, operator should fix the meta e.g. via HBCK.
      if (metaDescriptor != null && metaDescriptor.getColumnFamilyCount() > 0) {
        RemoteIterator<LocatedFileStatus> iterator = rootDirectoryFs.listFiles(metaTableDir, true);
        while (iterator.hasNext()) {
          LocatedFileStatus status = iterator.next();
          if (
            StoreFileInfo.isHFile(status.getPath())
              && HFile.isHFileFormat(rootDirectoryFs, status.getPath())
          ) {
            isPartial = false;
            break;
          }
        }
      }
    } finally {
      if (!isPartial) {
        throw new IOException("Meta table is not partial, please sideline this meta directory "
          + "or run HBCK to fix this meta table, e.g. rebuild the server hostname in ZNode for the "
          + "meta region");
      }
      return rootDirectoryFs.delete(metaTableDir, true);
    }

  }
}
