/**
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

package org.apache.hadoop.yarn.server.resourcemanager.recovery;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.DataInputByteBuffer;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.delegation.DelegationKey;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.proto.YarnServerResourceManagerServiceProtos.ApplicationAttemptStateDataProto;
import org.apache.hadoop.yarn.proto.YarnServerResourceManagerServiceProtos.ApplicationStateDataProto;
import org.apache.hadoop.yarn.security.client.RMDelegationTokenIdentifier;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.impl.pb.ApplicationAttemptStateDataPBImpl;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.impl.pb.ApplicationStateDataPBImpl;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.ZKUtil;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Private
@Unstable
public class ZKRMStateStore extends RMStateStore {

  public static final Log LOG = LogFactory.getLog(ZKRMStateStore.class);

  private static final String ROOT_ZNODE_NAME = "ZKRMStateRoot";
  private int numRetries;

  private String zkHostPort = null;
  private int zkSessionTimeout;
  private List<ACL> zkAcl;
  private String zkRootNodePath;
  private String rmDTSecretManagerRoot;
  private String rmAppRoot;
  private String dtSequenceNumberPath = null;

  @VisibleForTesting
  protected String znodeWorkingPath;

  @VisibleForTesting
  protected ZooKeeper zkClient;
  private ZooKeeper oldZkClient;

  @Override
  public synchronized void initInternal(Configuration conf) throws Exception {
    zkHostPort = conf.get(YarnConfiguration.ZK_RM_STATE_STORE_ADDRESS);
    if (zkHostPort == null) {
      throw new YarnRuntimeException("No server address specified for " +
          "zookeeper state store for Resource Manager recovery. " +
          YarnConfiguration.ZK_RM_STATE_STORE_ADDRESS + " is not configured.");
    }
    numRetries =
        conf.getInt(YarnConfiguration.ZK_RM_STATE_STORE_NUM_RETRIES,
            YarnConfiguration.DEFAULT_ZK_RM_STATE_STORE_NUM_RETRIES);
    znodeWorkingPath =
        conf.get(YarnConfiguration.ZK_RM_STATE_STORE_PARENT_PATH,
            YarnConfiguration.DEFAULT_ZK_RM_STATE_STORE_PARENT_PATH);
    zkSessionTimeout =
        conf.getInt(YarnConfiguration.ZK_RM_STATE_STORE_TIMEOUT_MS,
            YarnConfiguration.DEFAULT_ZK_RM_STATE_STORE_TIMEOUT_MS);
    // Parse authentication from configuration.
    String zkAclConf =
        conf.get(YarnConfiguration.ZK_RM_STATE_STORE_ACL,
            YarnConfiguration.DEFAULT_ZK_RM_STATE_STORE_ACL);
    zkAclConf = ZKUtil.resolveConfIndirection(zkAclConf);

    try {
      zkAcl = ZKUtil.parseACLs(zkAclConf);
    } catch (ZKUtil.BadAclFormatException bafe) {
      LOG.error("Invalid format for " + YarnConfiguration.ZK_RM_STATE_STORE_ACL);
      throw bafe;
    }

    zkRootNodePath = znodeWorkingPath + "/" + ROOT_ZNODE_NAME;
    rmDTSecretManagerRoot = zkRootNodePath + "/" + RM_DT_SECRET_MANAGER_ROOT;
    rmAppRoot = zkRootNodePath + "/" + RM_APP_ROOT;
  }

  @Override
  public synchronized void startInternal() throws Exception {
    // createConnection for future API calls
    createConnection();

    // ensure root dirs exist
    createRootDir(znodeWorkingPath);
    createRootDir(zkRootNodePath);
    createRootDir(rmDTSecretManagerRoot);
    createRootDir(rmAppRoot);
  }

  private void createRootDir(String rootPath) throws Exception {
    try {
      createWithRetries(rootPath, null, zkAcl, CreateMode.PERSISTENT);
    } catch (KeeperException ke) {
      if (ke.code() != Code.NODEEXISTS) {
        throw ke;
      }
    }
  }

  private synchronized void closeZkClients() throws IOException {
    if (zkClient != null) {
      try {
        zkClient.close();
      } catch (InterruptedException e) {
        throw new IOException("Interrupted while closing ZK", e);
      }
      zkClient = null;
    }
    if (oldZkClient != null) {
      try {
        oldZkClient.close();
      } catch (InterruptedException e) {
        throw new IOException("Interrupted while closing old ZK", e);
      }
      oldZkClient = null;
    }
  }

  @Override
  protected synchronized void closeInternal() throws Exception {
    closeZkClients();
  }

  @Override
  public synchronized RMState loadState() throws Exception {
    RMState rmState = new RMState();
    // recover DelegationTokenSecretManager
    loadRMDTSecretManagerState(rmState);
    // recover RM applications
    loadRMAppState(rmState);
    return rmState;
  }

  private synchronized void loadRMDTSecretManagerState(RMState rmState)
      throws Exception {
    List<String> childNodes = zkClient.getChildren(rmDTSecretManagerRoot, true);

    for (String childNodeName : childNodes) {
      if (childNodeName.startsWith(DELEGATION_TOKEN_SEQUENCE_NUMBER_PREFIX)) {
        rmState.rmSecretManagerState.dtSequenceNumber =
            Integer.parseInt(childNodeName.split("_")[1]);
        continue;
      }
      String childNodePath = getNodePath(rmDTSecretManagerRoot, childNodeName);
      byte[] childData = getDataWithRetries(childNodePath, true);

      ByteArrayInputStream is = new ByteArrayInputStream(childData);
      DataInputStream fsIn = new DataInputStream(is);
      try {
        if (childNodeName.startsWith(DELEGATION_KEY_PREFIX)) {
          DelegationKey key = new DelegationKey();
          key.readFields(fsIn);
          rmState.rmSecretManagerState.masterKeyState.add(key);
        } else if (childNodeName.startsWith(DELEGATION_TOKEN_PREFIX)) {
          RMDelegationTokenIdentifier identifier =
              new RMDelegationTokenIdentifier();
          identifier.readFields(fsIn);
          long renewDate = fsIn.readLong();
          rmState.rmSecretManagerState.delegationTokenState.put(identifier,
              renewDate);
        }
      } finally {
        is.close();
      }
    }
  }

  private synchronized void loadRMAppState(RMState rmState) throws Exception {
    List<String> childNodes = zkClient.getChildren(rmAppRoot, true);
    List<ApplicationAttemptState> attempts =
        new ArrayList<ApplicationAttemptState>();
    for (String childNodeName : childNodes) {
      String childNodePath = getNodePath(rmAppRoot, childNodeName);
      byte[] childData = getDataWithRetries(childNodePath, true);
      if (childNodeName.startsWith(ApplicationId.appIdStrPrefix)) {
        // application
        LOG.info("Loading application from znode: " + childNodeName);
        ApplicationId appId = ConverterUtils.toApplicationId(childNodeName);
        ApplicationStateDataPBImpl appStateData =
            new ApplicationStateDataPBImpl(
                ApplicationStateDataProto.parseFrom(childData));
        ApplicationState appState =
            new ApplicationState(appStateData.getSubmitTime(),
                appStateData.getApplicationSubmissionContext(),
                appStateData.getUser());
        if (!appId.equals(appState.context.getApplicationId())) {
          throw new YarnRuntimeException("The child node name is different " +
              "from the application id");
        }
        rmState.appState.put(appId, appState);
      } else if (childNodeName
          .startsWith(ApplicationAttemptId.appAttemptIdStrPrefix)) {
        // attempt
        LOG.info("Loading application attempt from znode: " + childNodeName);
        ApplicationAttemptId attemptId =
            ConverterUtils.toApplicationAttemptId(childNodeName);
        ApplicationAttemptStateDataPBImpl attemptStateData =
            new ApplicationAttemptStateDataPBImpl(
                ApplicationAttemptStateDataProto.parseFrom(childData));
        Credentials credentials = null;
        if (attemptStateData.getAppAttemptTokens() != null) {
          credentials = new Credentials();
          DataInputByteBuffer dibb = new DataInputByteBuffer();
          dibb.reset(attemptStateData.getAppAttemptTokens());
          credentials.readTokenStorageStream(dibb);
        }
        ApplicationAttemptState attemptState =
            new ApplicationAttemptState(attemptId,
                attemptStateData.getMasterContainer(), credentials);
        if (!attemptId.equals(attemptState.getAttemptId())) {
          throw new YarnRuntimeException("The child node name is different " +
              "from the application attempt id");
        }
        attempts.add(attemptState);
      } else {
        LOG.info("Unknown child node with name: " + childNodeName);
      }
    }

    // go through all attempts and add them to their apps
    for (ApplicationAttemptState attemptState : attempts) {
      ApplicationId appId = attemptState.getAttemptId().getApplicationId();
      ApplicationState appState = rmState.appState.get(appId);
      if (appState != null) {
        appState.attempts.put(attemptState.getAttemptId(), attemptState);
      } else {
        // the application znode may have been removed when the application
        // completed but the RM might have stopped before it could remove the
        // application attempt znodes
        LOG.info("Application node not found for attempt: "
            + attemptState.getAttemptId());
        deleteWithRetries(
            getNodePath(rmAppRoot, attemptState.getAttemptId().toString()),
            0);
      }
    }
  }

  @Override
  public synchronized void storeApplicationState(
      String appId, ApplicationStateDataPBImpl appStateDataPB) throws
      Exception {
    String nodeCreatePath = getNodePath(rmAppRoot, appId);

    if (LOG.isDebugEnabled()) {
      LOG.debug("Storing info for app: " + appId + " at: " + nodeCreatePath);
    }
    byte[] appStateData = appStateDataPB.getProto().toByteArray();
    createWithRetries(
        nodeCreatePath, appStateData, zkAcl, CreateMode.PERSISTENT);
  }

  @Override
  public synchronized void storeApplicationAttemptState(
      String attemptId, ApplicationAttemptStateDataPBImpl attemptStateDataPB)
      throws Exception {
    String nodeCreatePath = getNodePath(rmAppRoot, attemptId);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Storing info for attempt: " + attemptId + " at: "
          + nodeCreatePath);
    }
    byte[] attemptStateData = attemptStateDataPB.getProto().toByteArray();
    createWithRetries(nodeCreatePath, attemptStateData, zkAcl,
        CreateMode.PERSISTENT);
  }

  @Override
  public synchronized void removeApplicationState(ApplicationState appState)
      throws Exception {
    String appId = appState.getAppId().toString();
    String nodeRemovePath = getNodePath(rmAppRoot, appId);
    ArrayList<Op> opList = new ArrayList<Op>();
    opList.add(Op.delete(nodeRemovePath, 0));

    for (ApplicationAttemptId attemptId : appState.attempts.keySet()) {
      String attemptRemovePath = getNodePath(rmAppRoot, attemptId.toString());
      opList.add(Op.delete(attemptRemovePath, 0));
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Removing info for app: " + appId + " at: " + nodeRemovePath
          + " and its attempts.");
    }
    doMultiWithRetries(opList);
  }

  @Override
  protected synchronized void storeRMDelegationTokenAndSequenceNumberState(
      RMDelegationTokenIdentifier rmDTIdentifier, Long renewDate,
      int latestSequenceNumber) throws Exception {
    ArrayList<Op> opList = new ArrayList<Op>();
    // store RM delegation token
    String nodeCreatePath =
        getNodePath(rmDTSecretManagerRoot, DELEGATION_TOKEN_PREFIX
            + rmDTIdentifier.getSequenceNumber());
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    DataOutputStream fsOut = new DataOutputStream(os);
    try {
      rmDTIdentifier.write(fsOut);
      fsOut.writeLong(renewDate);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Storing RMDelegationToken_" +
            rmDTIdentifier.getSequenceNumber());
      }
      opList.add(Op.create(nodeCreatePath, os.toByteArray(), zkAcl,
          CreateMode.PERSISTENT));
    } finally {
      os.close();
    }

    // store sequence number
    String latestSequenceNumberPath =
        getNodePath(rmDTSecretManagerRoot,
            DELEGATION_TOKEN_SEQUENCE_NUMBER_PREFIX + latestSequenceNumber);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Storing " + DELEGATION_TOKEN_SEQUENCE_NUMBER_PREFIX +
          latestSequenceNumber);
    }

    if (dtSequenceNumberPath != null) {
      opList.add(Op.delete(dtSequenceNumberPath, 0));
    }
    opList.add(Op.create(latestSequenceNumberPath, null, zkAcl,
        CreateMode.PERSISTENT));
    dtSequenceNumberPath = latestSequenceNumberPath;
    doMultiWithRetries(opList);
  }

  @Override
  protected synchronized void removeRMDelegationTokenState(
      RMDelegationTokenIdentifier rmDTIdentifier) throws Exception {
    String nodeRemovePath =
        getNodePath(rmDTSecretManagerRoot, DELEGATION_TOKEN_PREFIX
            + rmDTIdentifier.getSequenceNumber());
    if (LOG.isDebugEnabled()) {
      LOG.debug("Removing RMDelegationToken_"
          + rmDTIdentifier.getSequenceNumber());
    }
    deleteWithRetries(nodeRemovePath, 0);
  }

  @Override
  protected synchronized void storeRMDTMasterKeyState(
      DelegationKey delegationKey) throws Exception {
    String nodeCreatePath =
        getNodePath(rmDTSecretManagerRoot, DELEGATION_KEY_PREFIX
            + delegationKey.getKeyId());
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    DataOutputStream fsOut = new DataOutputStream(os);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Storing RMDelegationKey_" + delegationKey.getKeyId());
    }
    delegationKey.write(fsOut);
    try {
      createWithRetries(nodeCreatePath, os.toByteArray(), zkAcl,
          CreateMode.PERSISTENT);
    } finally {
      os.close();
    }
  }

  @Override
  protected synchronized void removeRMDTMasterKeyState(
      DelegationKey delegationKey) throws Exception {
    String nodeRemovePath =
        getNodePath(rmDTSecretManagerRoot, DELEGATION_KEY_PREFIX
            + delegationKey.getKeyId());
    if (LOG.isDebugEnabled()) {
      LOG.debug("Removing RMDelegationKey_" + delegationKey.getKeyId());
    }
    deleteWithRetries(nodeRemovePath, 0);
  }

  // ZK related code
  /**
   * Watcher implementation which forward events to the ZKRMStateStore This
   * hides the ZK methods of the store from its public interface
   */
  private final class ForwardingWatcher implements Watcher {

    @Override
    public void process(WatchedEvent event) {
      try {
        ZKRMStateStore.this.processWatchEvent(event);
      } catch (Throwable t) {
        LOG.error("Failed to process watcher event " + event + ": "
            + StringUtils.stringifyException(t));
      }
    }
  }

  @VisibleForTesting
  public synchronized void processWatchEvent(WatchedEvent event)
      throws Exception {
    Event.EventType eventType = event.getType();
    LOG.info("Watcher event type: " + eventType + " with state:"
        + event.getState() + " for path:" + event.getPath() + " for " + this);

    if (eventType == Event.EventType.None) {

      // the connection state has changed
      switch (event.getState()) {
        case SyncConnected:
          LOG.info("ZKRMStateStore Session connected");
          if (oldZkClient != null) {
            // the SyncConnected must be from the client that sent Disconnected
            zkClient = oldZkClient;
            oldZkClient = null;
            ZKRMStateStore.this.notifyAll();
            LOG.info("ZKRMStateStore Session restored");
          }
          break;
        case Disconnected:
          LOG.info("ZKRMStateStore Session disconnected");
          oldZkClient = zkClient;
          zkClient = null;
          break;
        case Expired:
          // the connection got terminated because of session timeout
          // call listener to reconnect
          LOG.info("Session expired");
          createConnection();
          break;
        default:
          LOG.error("Unexpected Zookeeper" +
              " watch event state: " + event.getState());
          break;
      }
    }
  }

  @VisibleForTesting
  String getNodePath(String root, String nodeName) {
    return (root + "/" + nodeName);
  }

  @VisibleForTesting
  public String createWithRetries(
      final String path, final byte[] data, final List<ACL> acl,
      final CreateMode mode) throws Exception {
    return new ZKAction<String>() {
      @Override
      public String run() throws KeeperException, InterruptedException {
        return zkClient.create(path, data, acl, mode);
      }
    }.runWithRetries();
  }

  private void deleteWithRetries(final String path, final int version)
      throws Exception {
    new ZKAction<Void>() {
      @Override
      public Void run() throws KeeperException, InterruptedException {
        /**
         * Call exists() to leave a watch on the node denoted by path.
         * Delete node if exists. To pass the existence information to the
         * caller, call delete irrespective of whether node exists or not.
         */
        if (zkClient.exists(path, true) == null) {
          LOG.error("Trying to delete a path (" + path
              + ") that doesn't exist.");
        }
        zkClient.delete(path, version);
        return null;
      }
    }.runWithRetries();
  }

  private void doMultiWithRetries(final ArrayList<Op> opList) throws Exception {
    new ZKAction<Void>() {
      @Override
      public Void run() throws KeeperException, InterruptedException {
        zkClient.multi(opList);
        return null;
      }
    }.runWithRetries();
  }

  @VisibleForTesting
  public void setDataWithRetries(final String path, final byte[] data,
                                 final int version) throws Exception {
    new ZKAction<Void>() {
      @Override
      public Void run() throws KeeperException, InterruptedException {
        zkClient.setData(path, data, version);
        return null;
      }
    }.runWithRetries();
  }

  @VisibleForTesting
  public byte[] getDataWithRetries(final String path, final boolean watch)
      throws Exception {
    return new ZKAction<byte[]>() {
      @Override
      public byte[] run() throws KeeperException, InterruptedException {
        Stat stat = new Stat();
        return zkClient.getData(path, watch, stat);
      }
    }.runWithRetries();
  }

  private abstract class ZKAction<T> {
    // run() expects synchronization on ZKRMStateStore.this
    abstract T run() throws KeeperException, InterruptedException;

    T runWithCheck() throws Exception {
      long startTime = System.currentTimeMillis();
      synchronized (ZKRMStateStore.this) {
        while (zkClient == null) {
          ZKRMStateStore.this.wait(zkSessionTimeout);
          if (zkClient != null) {
            break;
          }
          if (System.currentTimeMillis() - startTime > zkSessionTimeout) {
            throw new IOException("Wait for ZKClient creation timed out");
          }
        }
        return run();
      }
    }

    T runWithRetries() throws Exception {
      int retry = 0;
      while (true) {
        try {
          return runWithCheck();
        } catch (KeeperException ke) {
          if (shouldRetry(ke.code()) && ++retry < numRetries) {
            continue;
          }
          throw ke;
        }
      }
    }
  }

  private static boolean shouldRetry(Code code) {
    switch (code) {
      case CONNECTIONLOSS:
      case OPERATIONTIMEOUT:
        return true;
      default:
        break;
    }
    return false;
  }

  private synchronized void createConnection()
      throws IOException, InterruptedException {
    closeZkClients();
    for (int retries = 0; retries < numRetries && zkClient == null;
        retries++) {
      try {
        zkClient = getNewZooKeeper();
      } catch (IOException ioe) {
        // Retry in case of network failures
        LOG.info("Failed to connect to the ZooKeeper on attempt - " +
            (retries + 1));
        ioe.printStackTrace();
      }
    }
    if (zkClient == null) {
      LOG.error("Unable to connect to Zookeeper");
      throw new YarnRuntimeException("Unable to connect to Zookeeper");
    }
    ZKRMStateStore.this.notifyAll();
    LOG.info("Created new ZK connection");
  }

  // protected to mock for testing
  @VisibleForTesting
  protected synchronized ZooKeeper getNewZooKeeper()
      throws IOException, InterruptedException {
    ZooKeeper zk = new ZooKeeper(zkHostPort, zkSessionTimeout, null);
    zk.register(new ForwardingWatcher());
    return zk;
  }
}
