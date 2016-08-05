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

import com.google.common.io.Files;
import com.google.common.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.local.storage.LocalStorage;
import org.eclipse.che.api.local.storage.LocalStorageFactory;
import org.eclipse.che.api.user.server.model.impl.UserImpl;
import org.eclipse.che.api.user.server.spi.UserDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Named;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static java.lang.System.currentTimeMillis;

/**
 * The component which migrates all the local data to different storage.
 * If it fails it will throw an appropriate exception and container start will be terminated.
 * If the migration is terminated it will continue migration from the fail point.
 *
 * <p>The migration strategy(for one entity type)
 * <ul>
 * <li>Load all the entity instances
 * <li>For each entity instance check whether such entity exists in the jpa based storage.
 * There is no need to check by anything else except of identifier.
 * <li>If an entity with such identifier exists then it is already migrated, otherwise
 * save the entity.
 * <li>If an error occurred during the entity saving stop the migration and populate the error
 * </ul>
 *
 * @author Yevhenii Voevodin
 */
@Singleton
public class LocalDataMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDataMigrator.class);

    @Inject
    @PostConstruct
    public void performMigration(@Named("che.conf.storage") String baseDir,
                                 UserDao userDao) throws Exception {
        final LocalStorageFactory factory = new LocalStorageFactory(baseDir);

        // Create all the objects needed for migration, the order is important
        final List<Migration<?>> migrations = new ArrayList<>();
        // Everyone depends on user, user must be migrated first
        migrations.add(new UserMigration(factory.create(LocalUserDaoImpl.FILENAME), userDao));

        long globalMigrationStart = -1;

        for (Migration<?> migration : migrations) {
            // If there is no file, then migration is already done, skip it
            if (!migration.getLocalStorage().getFile().exists()) continue;

            // Inform about the general migration start, if not informed
            if (globalMigrationStart == -1) {
                globalMigrationStart = currentTimeMillis();
                LOG.info("Components migration started", LocalDateTime.now());
            }

            // Migrate entities
            LOG.info("Starting migration of '{}' entities", migration.getEntityName());
            final long migrationStart = currentTimeMillis();
            final int migrated = migrateAll(migration);
            LOG.info("Migration of '{}' entities successfully finished. Migration time: {}ms, Migrated count: {}, Skipped count: {}",
                     migration.getEntityName(),
                     currentTimeMillis() - migrationStart,
                     migrated,
                     migration.getAllEntities().size() - migrated);

            // Backup the file, and remove the original one to avoid future migrations
            // e.g. /storage/users.json becomes /storage/users.json.backup
            final File file = migration.getLocalStorage().getFile();
            try {
                Files.move(file, new File(file, ".backup"));
            } catch (IOException x) {
                LOG.error("Couldn't move {} to {}.backup due to an error. Error: {}",
                          file.getAbsolutePath(),
                          file.getAbsolutePath(),
                          x.getLocalizedMessage());
                throw x;
            }
        }

        LOG.info("Components migration successfully finished. Migration time: {}", currentTimeMillis() - globalMigrationStart);
    }

    /**
     * Migrates entities and skips those which are already migrated.
     *
     * @param migration
     *         the migration
     * @param <T>
     *         the type of the migration
     * @return the count of migrated entities
     * @throws Exception
     *         when any error occurs
     */
    private static <T> int migrateAll(Migration<T> migration) throws Exception {
        int migrated = 0;
        for (T entity : migration.getAllEntities()) {
            // Skip those entities which are already migrated.
            // e.g. this check allows migration to fail and then continue from failed point
            try {
                // type-safe as the same migration is used
                @SuppressWarnings("unchecked")
                final boolean isMigrated = migration.isMigrated(entity);
                if (isMigrated) continue;
            } catch (Exception x) {
                LOG.error("Couldn't check if the entity '{}' is migrated due to occurred error", entity);
                throw x;
            }

            // The entity is not migrated, so migrate it
            try {
                migration.migrate(entity);
            } catch (Exception x) {
                LOG.error("Error migrating the entity '{}", entity);
                throw x;
            }
            migrated++;
        }
        return migrated;
    }

    public static class UserMigration extends Migration<UserImpl> {
        private final UserDao userDao;

        @Inject
        public UserMigration(LocalStorage localStorage, UserDao userDao) {
            super(UserImpl.class, localStorage);
            this.userDao = userDao;
        }

        @Override
        public List<UserImpl> getAllEntities() {
            return new ArrayList<>(localStorage.loadMap(new TypeToken<Map<String, UserImpl>>() {}).values());
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
        protected final Class<T>     migrationEntity;
        protected final LocalStorage localStorage;

        public Migration(Class<T> migrationEntity, LocalStorage localStorage) {
            this.migrationEntity = migrationEntity;
            this.localStorage = localStorage;
        }

        public String getEntityName() {
            return migrationEntity.getSimpleName();
        }

        public LocalStorage getLocalStorage() {
            return localStorage;
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
