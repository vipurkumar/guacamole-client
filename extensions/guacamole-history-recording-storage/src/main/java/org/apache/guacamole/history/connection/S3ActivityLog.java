/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole.history.connection;

import java.io.InputStream;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.language.TranslatableMessage;
import org.apache.guacamole.net.auth.AbstractActivityLog;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;

/**
 * ActivityLog implementation that exposes the content of an object stored in
 * an S3-compatible object store (such as MinIO).
 */
public class S3ActivityLog extends AbstractActivityLog {

    /**
     * The MinIO client used to access S3.
     */
    private final MinioClient minioClient;

    /**
     * The S3 bucket containing the recording.
     */
    private final String bucket;

    /**
     * The S3 object key of the recording.
     */
    private final String objectKey;

    /**
     * Creates a new S3ActivityLog that exposes the content of the given S3
     * object as an ActivityLog.
     *
     * @param type
     *     The type of this ActivityLog.
     *
     * @param description
     *     A human-readable message that describes this log.
     *
     * @param minioClient
     *     The MinIO client to use for accessing the S3 object.
     *
     * @param bucket
     *     The S3 bucket containing the object.
     *
     * @param objectKey
     *     The key of the S3 object.
     */
    public S3ActivityLog(Type type, TranslatableMessage description,
            MinioClient minioClient, String bucket, String objectKey) {
        super(type, description);
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.objectKey = objectKey;
    }

    @Override
    public long getSize() throws GuacamoleException {
        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build());
            return stat.size();
        }
        catch (Exception e) {
            throw new GuacamoleServerException("Failed to retrieve size of "
                    + "S3 object \"" + objectKey + "\" in bucket \""
                    + bucket + "\".", e);
        }
    }

    @Override
    public InputStream getContent() throws GuacamoleException {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build());
        }
        catch (Exception e) {
            throw new GuacamoleServerException("Failed to read S3 object \""
                    + objectKey + "\" from bucket \"" + bucket + "\".", e);
        }
    }

}
