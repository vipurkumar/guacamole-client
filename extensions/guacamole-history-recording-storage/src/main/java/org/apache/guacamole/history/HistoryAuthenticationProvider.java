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

package org.apache.guacamole.history;

import java.io.File;
import org.apache.guacamole.history.user.HistoryUserContext;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.environment.LocalEnvironment;
import org.apache.guacamole.net.auth.AbstractAuthenticationProvider;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.auth.UserContext;
import org.apache.guacamole.properties.FileGuacamoleProperty;
import org.apache.guacamole.properties.StringGuacamoleProperty;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AuthenticationProvider implementation which automatically associates history
 * entries with session recordings, typescripts, etc. History association is
 * determined by matching the history entry UUID with the filenames of files
 * located within a standardized/configurable directory. Supports both local
 * filesystem and S3-compatible storage (such as MinIO).
 */
public class HistoryAuthenticationProvider extends AbstractAuthenticationProvider {

    /**
     * Logger for this class.
     */
    private static final Logger logger = LoggerFactory.getLogger(HistoryAuthenticationProvider.class);

    /**
     * The default directory to search for associated session recordings, if
     * not overridden with the "recording-search-path" property.
     */
    private static final File DEFAULT_RECORDING_SEARCH_PATH = new File("/var/lib/guacamole/recordings");

    /**
     * The directory to search for associated session recordings. By default,
     * "/var/lib/guacamole/recordings" will be used.
     */
    private static final FileGuacamoleProperty RECORDING_SEARCH_PATH = new FileGuacamoleProperty() {

        @Override
        public String getName() {
            return "recording-search-path";
        }

    };

    /**
     * The recording storage type property. "s3" for S3-compatible storage,
     * anything else (or unset) for local filesystem.
     */
    private static final StringGuacamoleProperty RECORDING_STORAGE_TYPE = new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "recording-storage-type";
        }

    };

    /**
     * The S3 endpoint URL property.
     */
    private static final StringGuacamoleProperty RECORDING_S3_ENDPOINT = new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "recording-s3-endpoint";
        }

    };

    /**
     * The S3 bucket name property.
     */
    private static final StringGuacamoleProperty RECORDING_S3_BUCKET = new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "recording-s3-bucket";
        }

    };

    /**
     * The S3 access key ID property.
     */
    private static final StringGuacamoleProperty RECORDING_S3_ACCESS_KEY = new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "recording-s3-access-key";
        }

    };

    /**
     * The S3 secret access key property.
     */
    private static final StringGuacamoleProperty RECORDING_S3_SECRET_KEY = new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "recording-s3-secret-key";
        }

    };

    /**
     * The S3 region property.
     */
    private static final StringGuacamoleProperty RECORDING_S3_REGION = new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "recording-s3-region";
        }

    };

    /**
     * Cached MinIO client instance, initialized lazily.
     */
    private static MinioClient minioClient = null;

    /**
     * Whether S3 storage is enabled.
     */
    private static Boolean s3Enabled = null;

    /**
     * Cached S3 bucket name.
     */
    private static String s3Bucket = null;

    /**
     * Returns whether S3 storage is enabled for recordings.
     *
     * @return
     *     true if recording storage type is set to "s3", false otherwise.
     *
     * @throws GuacamoleException
     *     If the configuration cannot be read.
     */
    public static boolean isS3Enabled() throws GuacamoleException {
        if (s3Enabled == null) {
            Environment environment = LocalEnvironment.getInstance();
            String storageType = environment.getProperty(RECORDING_STORAGE_TYPE);
            s3Enabled = "s3".equals(storageType);
        }
        return s3Enabled;
    }

    /**
     * Returns the S3 bucket name configured for recording storage.
     *
     * @return
     *     The S3 bucket name.
     *
     * @throws GuacamoleException
     *     If the configuration cannot be read.
     */
    public static String getS3Bucket() throws GuacamoleException {
        if (s3Bucket == null) {
            Environment environment = LocalEnvironment.getInstance();
            s3Bucket = environment.getRequiredProperty(RECORDING_S3_BUCKET);
        }
        return s3Bucket;
    }

    /**
     * Returns a shared MinIO client instance configured from
     * guacamole.properties. The client is created lazily on first access.
     *
     * @return
     *     A configured MinioClient instance.
     *
     * @throws GuacamoleException
     *     If required S3 configuration properties are missing or invalid.
     */
    public static synchronized MinioClient getMinioClient() throws GuacamoleException {

        if (minioClient != null)
            return minioClient;

        Environment environment = LocalEnvironment.getInstance();

        String endpoint = environment.getRequiredProperty(RECORDING_S3_ENDPOINT);
        String accessKey = environment.getRequiredProperty(RECORDING_S3_ACCESS_KEY);
        String secretKey = environment.getRequiredProperty(RECORDING_S3_SECRET_KEY);
        String region = environment.getProperty(RECORDING_S3_REGION);

        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey);

        if (region != null)
            builder.region(region);

        minioClient = builder.build();

        logger.info("S3 recording storage configured: endpoint={}, bucket={}",
                endpoint, getS3Bucket());

        return minioClient;
    }

    /**
     * Returns the directory that should be searched for session recordings
     * associated with history entries.
     *
     * @return
     *     The directory that should be searched for session recordings
     *     associated with history entries.
     *
     * @throws GuacamoleException
     *     If the "recording-search-path" property cannot be parsed.
     */
    public static File getRecordingSearchPath() throws GuacamoleException {
        Environment environment = LocalEnvironment.getInstance();
        return environment.getProperty(RECORDING_SEARCH_PATH,
                DEFAULT_RECORDING_SEARCH_PATH);
    }

    @Override
    public String getIdentifier() {
        return "recording-storage";
    }

    @Override
    public UserContext decorate(UserContext context,
            AuthenticatedUser authenticatedUser, Credentials credentials)
            throws GuacamoleException {
        return new HistoryUserContext(context.self(), context);
    }

}
