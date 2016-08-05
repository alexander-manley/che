/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.api.local;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.local.storage.LocalStorage;
import org.eclipse.che.api.local.storage.LocalStorageFactory;
import org.eclipse.che.api.user.server.model.impl.UserImpl;
import org.eclipse.che.api.user.server.spi.UserDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;

/**
 * The component which migrates all the local data to jpa based storages.
 * If it fails it will throw an appropriate exception and container start will be terminated.
 * If the migration is terminated it will continue migration from the logic reportFail point.
 *
 * <p>The migration strategy(for one entity type)
 * <ul>
 * <li>Load all the entity instances
 * <li>For each entity instance check whether such entity exists in the jpa based storage.
 * There is no need to check by anything else except of identifier.
 * <li>If an entity with such identifier exists then it is already migrated, otherwise
 * save the entity.
 * <li>If an error occurred during the entity saving stop the migration and populate the error
 * <li>If the migration for one entity type is
 * </ul>
 *
 * @author Yevhenii Voevodin
 */
@Singleton
public class LocalDataMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDataMigrator.class);

    @Inject
    @PostConstruct
    public void performMigration(UserMigration userMigration,
                                 LocalStorageFactory storageFactory) throws Exception {
        long migrationStartTime = -1;

        // The order of migrations is important
        final List<Migration> migrations = new ArrayList<>();
        migrations.add(userMigration);

        for (Migration migration : migrations) {
            final String filename = migration.getFilename();

            // Check whether this type of entities is already migrated
            final LocalStorage storage;
            try {
                storage = storageFactory.create(filename);
            } catch (IOException x) {
                throw new ServerException(format("Couldn't check whether migration needed for entity '%s'. Error '%s'",
                                                 migration.getEntityName(),
                                                 x.getLocalizedMessage()),
                                          x);
            }
            final Path path = storage.getFile().toPath();
            if (!Files.exists(path)) {
                // If there is no local file then it was fully migrated
                continue;
            }

            // Inform user about the general migration start, if not informed
            if (migrationStartTime == -1) {
                migrationStartTime = System.currentTimeMillis();
                LOG.info("Components migration started. Time: '{}'", LocalDateTime.now());
            }

            // Start migration of entities
            LOG.info("Starting migration of '{}'", migration.getEntityName());
            long entitiesMigrationStart = System.currentTimeMillis();

            final List entities = migration.getAllEntities();
            int migratedCount = 0;
            for (Object entity : entities) {
                // Skip those entities which are already migrated.
                // e.g. this check allows migration to fail and then continue from failed point
                try {
                    // type-safe as the same migration is used
                    @SuppressWarnings("unchecked")
                    boolean isMigrated = migration.isMigrated(entity);
                    if (isMigrated) {
                        continue;
                    }
                } catch (Exception x) {
                    LOG.error("Couldn't check if the entity '{}' is migrated due to occurred error. Migration failed.", entity);
                    throw x;
                }
            }

            // Migrate the entity

        }
    }

    @Singleton
    public static class UserMigration extends Migration<UserImpl> {
        private final LocalUserDaoImpl localDao;
        private final UserDao          userDao;

        @Inject
        public UserMigration(LocalUserDaoImpl localDao, UserDao userDao) {
            super(UserImpl.class, LocalUserDaoImpl.FILENAME);
            this.localDao = localDao;
            this.userDao = userDao;
        }

        @Override
        public List<UserImpl> getAllEntities() {
            synchronized (localDao) {
                return localDao.users.values()
                                     .stream()
                                     .map(UserImpl::new)
                                     .collect(toList());
            }
        }

        @Override
        public void migrate(UserImpl entity) throws Exception {
            userDao.create(entity);
        }

        @Override
        public boolean isMigrated(UserImpl entity) throws Exception {
            return notFoundAsTrue(() -> userDao.getById(entity.getId()));
        }
    }

    public static abstract class Migration<T> {
        protected final Class<T> migrationEntity;
        protected final String   filename;

        public Migration(Class<T> migrationEntity, String filename) {
            this.migrationEntity = migrationEntity;
            this.filename = filename;
        }

        public String getEntityName() {
            return migrationEntity.getSimpleName();
        }

        public String getFilename() {
            return filename;
        }

        public abstract List<T> getAllEntities() throws Exception;

        public abstract void migrate(T entity) throws Exception;

        public abstract boolean isMigrated(T entity) throws Exception;
    }

    public static boolean notFoundAsTrue(Callable<?> action) throws Exception {
        try {
            action.call();
        } catch (NotFoundException x) {
            return true;
        }
        return false;
    }
}
