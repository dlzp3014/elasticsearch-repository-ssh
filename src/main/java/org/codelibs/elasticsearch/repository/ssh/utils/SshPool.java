/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.codelibs.elasticsearch.repository.ssh.utils;

import java.security.AccessController;
import java.security.PrivilegedAction;

import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

public class SshPool {
    private GenericKeyedObjectPool<SshConfig, Session> pool;

    private SshConfig config;

    /**
     * create SshPool with default GenericKeyedObjectPoolConfig
     * @param config
     */
    public SshPool(final SshConfig config) {
        this(config, new GenericKeyedObjectPoolConfig());
    }

    /**
     * create Ssh Pool
     * @param config
     * @param poolConfig
     */
    public SshPool(final SshConfig config,
            final GenericKeyedObjectPoolConfig poolConfig) {
        this.config = config;

        this.pool = AccessController.doPrivileged(
                new PrivilegedAction<GenericKeyedObjectPool<SshConfig, Session>>() {
                    @Override
                    public GenericKeyedObjectPool<SshConfig, Session> run() {
                        try {
                            return new GenericKeyedObjectPool<>(
                                    new SshPoolFactory(), poolConfig);
                        } catch (final Exception e) {
                            throw new SshPoolException("could not init pool.",
                                    e);
                        }
                    }
                });
    }

    /**
     * borrow session from pool
     * @return
     */
    public Session getSession() {
        try {
            return pool.borrowObject(config);
        } catch (final Exception e) {
            throw new SshPoolException("could not get session from pool.", e);
        }
    }

    /**
     * return session to pool
     * @param session
     */
    public void returnSession(final Session session) {
        try {
            pool.returnObject(config, session);
        } catch (final IllegalStateException e) {
            //ignore
        }
    }

    /**
     * invalidate the broken session
     * @param session
     */
    public void invalidateSession(final Session session) {
        try {
            pool.invalidateObject(config, session);
        } catch (final Exception e) {
            throw new SshPoolException("could not invalidate session.", e);
        }
    }

    public void close() {
        pool.close();
    }

    private static class SshPoolFactory
            extends BaseKeyedPooledObjectFactory<SshConfig, Session> {

        @Override
        public Session create(final SshConfig config) throws Exception {
            final JSch jsch = new JSch();
            if (config.getKnownHosts() != null
                    && !config.getKnownHosts().isEmpty()) {
                jsch.setKnownHosts(config.getKnownHosts());
            }

            if (config.getPrivateKey() != null
                    && !config.getPrivateKey().isEmpty()) {
                if (config.getPassphrase() != null) {
                    jsch.addIdentity(config.getPrivateKey(),
                            config.getPassphrase());
                } else {
                    jsch.addIdentity(config.getPrivateKey());
                }
            }

            final Session session = jsch.getSession(config.getUsername(),
                    config.getHost(), config.getPort());
            if (config.getPassword() != null) {
                session.setPassword(config.getPassword());
            }
            if (config.isIgnoreHostKeyChecking()) {
                session.setConfig("StrictHostKeyChecking", "no");
            }

            session.connect();

            return session;
        }

        @Override
        public PooledObject<Session> wrap(final Session session) {
            return new DefaultPooledObject<>(session);
        }

        @Override
        public boolean validateObject(final SshConfig key,
                final PooledObject<Session> pooledObject) {
            return pooledObject.getObject().isConnected();
        }

        @Override
        public void destroyObject(final SshConfig key,
                final PooledObject<Session> pooledObject) {
            if (pooledObject != null) {
                pooledObject.getObject().disconnect();
            }
        }
    }

}
