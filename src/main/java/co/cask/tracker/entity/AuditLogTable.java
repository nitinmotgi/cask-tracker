/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.tracker.entity;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.dataset.DatasetSpecification;
import co.cask.cdap.api.dataset.lib.AbstractCloseableIterator;
import co.cask.cdap.api.dataset.lib.AbstractDataset;
import co.cask.cdap.api.dataset.lib.CloseableIterator;
import co.cask.cdap.api.dataset.module.EmbeddedDataset;
import co.cask.cdap.api.dataset.table.Put;
import co.cask.cdap.api.dataset.table.Row;
import co.cask.cdap.api.dataset.table.Scan;
import co.cask.cdap.api.dataset.table.Scanner;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.proto.audit.AuditMessage;
import co.cask.cdap.proto.audit.AuditPayload;
import co.cask.cdap.proto.audit.AuditType;
import co.cask.cdap.proto.audit.payload.access.AccessPayload;
import co.cask.cdap.proto.audit.payload.metadata.MetadataPayload;
import co.cask.cdap.proto.codec.AuditMessageTypeAdapter;
import co.cask.cdap.proto.codec.EntityIdTypeAdapter;
import co.cask.cdap.proto.element.EntityType;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.proto.id.DatasetModuleId;
import co.cask.cdap.proto.id.DatasetTypeId;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.FlowletId;
import co.cask.cdap.proto.id.FlowletQueueId;
import co.cask.cdap.proto.id.NamespacedArtifactId;
import co.cask.cdap.proto.id.NamespacedId;
import co.cask.cdap.proto.id.NotificationFeedId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.id.ProgramRunId;
import co.cask.cdap.proto.id.ScheduleId;
import co.cask.cdap.proto.id.StreamId;
import co.cask.cdap.proto.id.StreamViewId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.util.UUID;

/**
 * Creates the key and scan key for storing data in the AuditLog table.
 */
public final class AuditLogTable extends AbstractDataset {
  private static final String DEFAULT_USER = "unknown";
  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapter(AuditMessage.class, new AuditMessageTypeAdapter())
    .registerTypeAdapter(EntityId.class, new EntityIdTypeAdapter())
    .create();

  private final Table auditLog;

  public AuditLogTable(DatasetSpecification spec, @EmbeddedDataset("auditLog") Table auditLogDataset) {
    super(spec.getName(), auditLogDataset);
    this.auditLog = auditLogDataset;
  }

  /**
   * Scans the table for entities between a given start and end time.
   * @param namespace the namespace where the entity exists
   * @param entityType the type of the entity
   * @param entityName the name of the entity
   * @param startTime the starting time for the scan in seconds
   * @param endTime the ending time for the scan in seconds
   * @return
   */
  public CloseableIterator<AuditMessage> scan(String namespace,
                      String entityType,
                      String entityName,
                      long startTime,
                      long endTime) {
    Scanner scanner = auditLog.scan(
      new Scan(getScanKey(namespace, entityType, entityName, startTime),
               getScanKey(namespace, entityType, entityName, endTime))
    );
    return new AuditMessageIterator(scanner);
  }

  public void write(AuditMessage auditMessage) throws IOException {
    EntityId entityId = auditMessage.getEntityId();
    if (entityId instanceof NamespacedId) {
      String namespace = ((NamespacedId) entityId).getNamespace();
      EntityType entityType = entityId.getEntity();
      String type = entityType.name().toLowerCase();
      String name;
      // Unfortunately, there's no generic way to get the name of the entity
      // so we need this switch statement and a bunch of casting.
      switch (entityType) {
        case APPLICATION:
          name = ((ApplicationId) entityId).getApplication();
          break;
        case ARTIFACT:
          name = ((NamespacedArtifactId) entityId).getArtifact();
          break;
        case DATASET:
          name = ((DatasetId) entityId).getDataset();
          break;
        case DATASET_MODULE:
          name = ((DatasetModuleId) entityId).getModule();
          break;
        case DATASET_TYPE:
          name = ((DatasetTypeId) entityId).getType();
          break;
        case FLOWLET:
          name = ((FlowletId) entityId).getFlowlet();
          break;
        case FLOWLET_QUEUE:
          name = ((FlowletQueueId) entityId).getQueue();
          break;
        case NOTIFICATION_FEED:
          name = ((NotificationFeedId) entityId).getFeed();
          break;
        case PROGRAM:
          name = ((ProgramId) entityId).getProgram();
          break;
        case PROGRAM_RUN:
          name = ((ProgramRunId) entityId).getRun();
          break;
        case SCHEDULE:
          name = ((ScheduleId) entityId).getSchedule();
          break;
        case STREAM:
          name = ((StreamId) entityId).getStream();
          break;
        case STREAM_VIEW:
          name = ((StreamViewId) entityId).getView();
          break;
        default:
          throw new IOException("Unknown entity type: " + entityType);
      }
      String user = auditMessage.getUser();
      if (user == null || user.isEmpty()) {
        user = DEFAULT_USER;
      }
      // The key allows for scanning by namespace, entity, and time. A UUID
      // is added to ensure the key is unique.
      auditLog.put(
        new Put(getKey(namespace, type, name, auditMessage.getTime()))
          .add("timestamp", auditMessage.getTime())
          .add("entityId", GSON.toJson(auditMessage.getEntityId()))
          .add("user", user)
          .add("actionType", auditMessage.getType().name())
          .add("entityType", type)
          .add("entityName", name)
          .add("metadata", GSON.toJson(auditMessage.getPayload())));
    } else {
      throw new IOException("Entity does not have a namespace and was not written to the auditLog: " + entityId);
    }
  }

  /**
   * This method generates a unique key to use for the data table.
   * @param namespace the namespace where the entity exists
   * @param entityType the type of the entity
   * @param entityName the name of the entity
   * @param timestamp the timestamp of the entity to search for
   * @return A string that can be used as a key in the dataset
   */
  private String getKey(String namespace,
                        String entityType,
                        String entityName,
                        long timestamp) {
    return String.format("%s-%s-%s-%s-%s",
                         namespace,
                         entityType,
                         entityName,
                         timestamp,
                         UUID.randomUUID().toString());
  }

  /**
   * This method generates the key to use for scanning the data table.
   * @param namespace the namespace where the entity exists
   * @param entityType the type of the entity
   * @param entityName the name of the entity
   * @param timestamp the timestamp of the entity to search for
   * @return A byte array that can be used to scan the dataset
   */
  private byte[] getScanKey(String namespace,
                            String entityType,
                            String entityName,
                            long timestamp) {
    return Bytes.toBytes(String.format("%s-%s-%s-%s", namespace, entityType, entityName, timestamp));
  }

  /**
   * Helper method to build the AuditMessage from a row.
   * @param row the row from the scanner to build the AuditMessage from
   * @return a new AuditMessage based on the information in the row
   */
  private static AuditMessage createAuditMessage(Row row) {
    EntityId entityId = GSON.fromJson(row.getString("entityId"), EntityId.class);
    AuditType messageType = AuditType.valueOf(row.getString("actionType"));
    AuditPayload payload;
    switch (messageType) {
      case METADATA_CHANGE:
        payload = GSON.fromJson(row.getString("metadata"), MetadataPayload.class);
        break;
      case ACCESS:
        payload = GSON.fromJson(row.getString("metadata"), AccessPayload.class);
        break;
      default:
        payload = AuditPayload.EMPTY_PAYLOAD;
    }
    return new AuditMessage(row.getLong("timestamp"),
                            entityId,
                            row.getString("user"),
                            messageType,
                            payload);
  }

  /**
   * A closable iterator for moving through AuditMessages returned by a scan.
   */
  private static final class AuditMessageIterator extends AbstractCloseableIterator<AuditMessage> {
    private final Scanner scanner;
    private Row nextRow;

    AuditMessageIterator(Scanner scanner) {
      this.scanner = scanner;
      nextRow = scanner.next();
    }

    @Override
    protected AuditMessage computeNext() {
      if (nextRow == null) {
        return endOfData();
      }
      Row current = nextRow;
      nextRow = scanner.next();
      return createAuditMessage(current);
    }

    @Override
    public void close() {
      scanner.close();
    }
  }
}
