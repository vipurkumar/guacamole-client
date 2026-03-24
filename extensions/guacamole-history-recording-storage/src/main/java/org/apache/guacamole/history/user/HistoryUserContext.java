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

package org.apache.guacamole.history.user;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.environment.LocalEnvironment;
import org.apache.guacamole.history.HistoryAuthenticationProvider;
import org.apache.guacamole.history.connection.HistoryConnection;
import org.apache.guacamole.history.connection.RecordedConnectionActivityRecordSet;
import org.apache.guacamole.net.auth.ActivityRecordSet;
import org.apache.guacamole.net.auth.Connection;
import org.apache.guacamole.net.auth.ConnectionGroup;
import org.apache.guacamole.net.auth.ConnectionRecord;
import org.apache.guacamole.net.auth.DecoratingDirectory;
import org.apache.guacamole.net.auth.Directory;
import org.apache.guacamole.net.auth.TokenInjectingUserContext;
import org.apache.guacamole.net.auth.User;
import org.apache.guacamole.net.auth.UserContext;
import org.apache.guacamole.properties.StringGuacamoleProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UserContext implementation that automatically defines ActivityLogs for
 * files that relate to history entries. When S3 storage is configured,
 * connection tokens are injected so that guacd receives the S3 parameters
 * needed to write recordings directly to S3.
 */
public class HistoryUserContext extends TokenInjectingUserContext {

    /**
     * Logger for this class.
     */
    private static final Logger logger = LoggerFactory.getLogger(HistoryUserContext.class);

    /**
     * The name of the parameter token that contains the automatically-searched
     * history recording/log path.
     */
    private static final String HISTORY_PATH_TOKEN_NAME = "HISTORY_PATH";

    /**
     * The current Guacamole user.
     */
    private final User currentUser;

    /**
     * Creates a new HistoryUserContext that wraps the given UserContext,
     * automatically associating history entries with ActivityLogs based on
     * related files (session recordings, typescripts, etc.).
     *
     * @param currentUser
     *     The current Guacamole user.
     *
     * @param context
     *     The UserContext to wrap.
     */
    public HistoryUserContext(User currentUser, UserContext context) {
        super(context);
        this.currentUser = currentUser;
    }

    /**
     * Returns the tokens which should be added to an in-progress call to
     * connect() for any Connectable object.
     *
     * @return
     *     The tokens which should be added to the in-progress call to
     *     connect().
     *
     * @throws GuacamoleException
     *     If the relevant tokens cannot be generated.
     */
    private Map<String, String> getTokens() throws GuacamoleException {

        Map<String, String> tokens = new HashMap<>();

        // Always provide the history path token
        tokens.put(HISTORY_PATH_TOKEN_NAME,
                HistoryAuthenticationProvider.getRecordingSearchPath().getAbsolutePath());

        // If S3 storage is enabled, inject S3 parameters as tokens so they
        // can be used in connection parameters via ${HISTORY_RECORDING_S3_*}
        if (HistoryAuthenticationProvider.isS3Enabled()) {
            Environment environment = LocalEnvironment.getInstance();

            String endpoint = environment.getProperty(
                    new StringGuacamoleProperty() {
                        @Override public String getName() { return "recording-s3-endpoint"; }
                    });
            String bucket = environment.getProperty(
                    new StringGuacamoleProperty() {
                        @Override public String getName() { return "recording-s3-bucket"; }
                    });
            String accessKey = environment.getProperty(
                    new StringGuacamoleProperty() {
                        @Override public String getName() { return "recording-s3-access-key"; }
                    });
            String secretKey = environment.getProperty(
                    new StringGuacamoleProperty() {
                        @Override public String getName() { return "recording-s3-secret-key"; }
                    });
            String region = environment.getProperty(
                    new StringGuacamoleProperty() {
                        @Override public String getName() { return "recording-s3-region"; }
                    });

            if (endpoint != null)
                tokens.put("HISTORY_RECORDING_S3_ENDPOINT", endpoint);
            if (bucket != null)
                tokens.put("HISTORY_RECORDING_S3_BUCKET", bucket);
            if (accessKey != null)
                tokens.put("HISTORY_RECORDING_S3_ACCESS_KEY", accessKey);
            if (secretKey != null)
                tokens.put("HISTORY_RECORDING_S3_SECRET_KEY", secretKey);
            if (region != null)
                tokens.put("HISTORY_RECORDING_S3_REGION", region);

            tokens.put("HISTORY_RECORDING_STORAGE_TYPE", "s3");
        }

        return tokens;
    }

    @Override
    protected Map<String, String> getTokens(ConnectionGroup connectionGroup)
            throws GuacamoleException {
        return getTokens();
    }

    @Override
    protected Map<String, String> getTokens(Connection connection)
            throws GuacamoleException {
        return getTokens();
    }

    @Override
    public Directory<Connection> getConnectionDirectory() throws GuacamoleException {
        return new DecoratingDirectory<Connection>(super.getConnectionDirectory()) {

            @Override
            protected Connection decorate(Connection object) {
                return new HistoryConnection(currentUser, object);
            }

            @Override
            protected Connection undecorate(Connection object) throws GuacamoleException {
                return ((HistoryConnection) object).getWrappedConnection();
            }

        };
    }

    @Override
    public ActivityRecordSet<ConnectionRecord> getConnectionHistory()
            throws GuacamoleException {
        return new RecordedConnectionActivityRecordSet(currentUser, super.getConnectionHistory());
    }

}
