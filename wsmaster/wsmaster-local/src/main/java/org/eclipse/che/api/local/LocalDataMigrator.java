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

import com.google.common.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.che.api.local.storage.stack.StackLocalStorage;
import org.eclipse.che.api.machine.server.model.impl.SnapshotImpl;
import org.eclipse.che.api.machine.server.recipe.RecipeImpl;
import org.eclipse.che.api.machine.server.spi.RecipeDao;
import org.eclipse.che.api.machine.server.spi.SnapshotDao;
import org.eclipse.che.api.ssh.server.model.impl.SshPairImpl;
import org.eclipse.che.api.ssh.server.spi.SshDao;
import org.eclipse.che.api.workspace.server.model.impl.WorkspaceImpl;
import org.eclipse.che.api.workspace.server.model.impl.stack.StackImpl;
import org.eclipse.che.api.workspace.server.spi.StackDao;
import org.eclipse.che.api.workspace.server.spi.WorkspaceDao;
import org.eclipse.che.commons.lang.Pair;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.local.storage.LocalStorage;
import org.eclipse.che.api.local.storage.LocalStorageFactory;
import org.eclipse.che.api.user.server.model.impl.ProfileImpl;
import org.eclipse.che.api.user.server.model.impl.UserImpl;
import org.eclipse.che.api.user.server.spi.PreferenceDao;
import org.eclipse.che.api.user.server.spi.ProfileDao;
import org.eclipse.che.api.user.server.spi.UserDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Named;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static java.lang.System.currentTimeMillis;
import static java.nio.file.Files.exists;

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
                                 UserDao userDao,
                                 ProfileDao profileDao,
                                 PreferenceDao preferenceDao,
                                 SshDao sshDao,
                                 WorkspaceDao workspaceDao,
                                 SnapshotDao snapshotDao,
                                 RecipeDao recipeDao,
                                 StackDao stackDao) throws Exception {
        final LocalStorageFactory factory = new LocalStorageFactory(baseDir);

        // Create all the objects needed for migration, the order is important
        final List<Migration<?>> migrations = new ArrayList<>();
        migrations.add(new UserMigration(factory.create(LocalUserDaoImpl.FILENAME), userDao));
        migrations.add(new ProfileMigration(factory.create(LocalProfileDaoImpl.FILENAME), profileDao));
        migrations.add(new PreferencesMigration(factory.create(LocalPreferenceDaoImpl.FILENAME), preferenceDao));
        migrations.add(new SshKeyMigration(factory.create(LocalSshDaoImpl.FILENAME), sshDao));
        migrations.add(new WorkspaceMigration(factory.create(LocalWorkspaceDaoImpl.FILENAME), workspaceDao));
        migrations.add(new SnapshotMigration(factory.create(LocalSnapshotDaoImpl.FILENAME), snapshotDao));
        migrations.add(new RecipeMigration(factory.create(LocalRecipeDaoImpl.FILENAME), recipeDao));
        migrations.add(new StackMigration(factory.create(StackLocalStorage.STACK_STORAGE_FILE), stackDao));

        long globalMigrationStart = -1;

        for (Migration<?> migration : migrations) {
            // If there is no file, then migration is already done, skip it
            if (!exists(migration.getPath())) continue;

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
            final Path dataFile = migration.getPath();
            try {
                Files.move(dataFile, dataFile.resolveSibling(dataFile.getFileName().toString() + ".backup"));
            } catch (IOException x) {
                LOG.error("Couldn't move {} to {}.backup due to an error. Error: {}",
                          dataFile.toString(),
                          dataFile.toString(),
                          x.getLocalizedMessage());
                throw x;
            }
        }

        LOG.info("Components migration successfully finished. Total migration time: {}ms", currentTimeMillis() - globalMigrationStart);
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

    /**
     * The base class for all migrations.
     *
     * @param <T>
     *         the type of the entities migrated by this migration
     */
    public static abstract class Migration<T> {
        protected final String       entityName;
        protected final LocalStorage storage;

        public Migration(String entityName, LocalStorage localStorage) {
            this.entityName = entityName;
            this.storage = localStorage;
        }

        public String getEntityName() {
            return entityName;
        }

        public Path getPath() {
            return storage.getFile().toPath();
        }

        public abstract List<T> getAllEntities() throws Exception;

        public abstract void migrate(T entity) throws Exception;

        public abstract boolean isMigrated(T entity) throws Exception;
    }

    public static class UserMigration extends Migration<UserImpl> {
        private final UserDao userDao;

        public UserMigration(LocalStorage localStorage, UserDao userDao) {
            super("User", localStorage);
            this.userDao = userDao;
        }

        @Override
        public List<UserImpl> getAllEntities() {
            return new ArrayList<>(storage.loadMap(new TypeToken<Map<String, UserImpl>>() {}).values());
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

    public static class ProfileMigration extends Migration<ProfileImpl> {
        private final ProfileDao profileDao;

        public ProfileMigration(LocalStorage localStorage, ProfileDao profileDao) {
            super("Profile", localStorage);
            this.profileDao = profileDao;
        }

        @Override
        public List<ProfileImpl> getAllEntities() throws Exception {
            return new ArrayList<>(storage.loadMap(new TypeToken<Map<String, ProfileImpl>>() {}).values());
        }

        @Override
        public void migrate(ProfileImpl entity) throws Exception {
            profileDao.create(entity);
        }

        @Override
        public boolean isMigrated(ProfileImpl entity) throws Exception {
            return notFoundAsTrue(() -> profileDao.getById(entity.getUserId()));
        }
    }

    public static class PreferencesMigration extends Migration<Pair<String, Map<String, String>>> {

        private final PreferenceDao preferenceDao;

        public PreferencesMigration(LocalStorage localStorage, PreferenceDao preferenceDao) {
            super("Preferences", localStorage);
            this.preferenceDao = preferenceDao;
        }

        @Override
        public List<Pair<String, Map<String, String>>> getAllEntities() throws Exception {
            return storage.loadMap(new TypeToken<Map<String, Map<String, String>>>() {})
                          .entrySet()
                          .stream()
                          .map(e -> Pair.of(e.getKey(), e.getValue()))
                          .collect(Collectors.toList());
        }

        @Override
        public void migrate(Pair<String, Map<String, String>> entity) throws Exception {
            preferenceDao.setPreferences(entity.first, entity.second);
        }

        @Override
        public boolean isMigrated(Pair<String, Map<String, String>> entity) throws Exception {
            return !preferenceDao.getPreferences(entity.first).isEmpty();
        }
    }

    public static class SshKeyMigration extends Migration<SshPairImpl> {

        private final SshDao sshDao;

        public SshKeyMigration(LocalStorage localStorage, SshDao sshDao) {
            super("SshKeyPair", localStorage);
            this.sshDao = sshDao;
        }

        @Override
        public List<SshPairImpl> getAllEntities() throws Exception {
            return storage.loadList(new TypeToken<List<SshPairImpl>>() {});
        }

        @Override
        public void migrate(SshPairImpl entity) throws Exception {
            sshDao.create(entity);
        }

        @Override
        public boolean isMigrated(SshPairImpl entity) throws Exception {
            return notFoundAsTrue(() -> sshDao.get(entity.getOwner(), entity.getService(), entity.getName()));
        }
    }

    public static class WorkspaceMigration extends Migration<WorkspaceImpl> {

        private final WorkspaceDao workspaceDao;

        public WorkspaceMigration(LocalStorage localStorage, WorkspaceDao workspaceDao) {
            super("Workspace", localStorage);
            this.workspaceDao = workspaceDao;
        }

        @Override
        public List<WorkspaceImpl> getAllEntities() throws Exception {
            return new ArrayList<>(storage.loadMap(new TypeToken<Map<String, WorkspaceImpl>>() {}).values());
        }

        @Override
        public void migrate(WorkspaceImpl entity) throws Exception {
            workspaceDao.create(entity);
        }

        @Override
        public boolean isMigrated(WorkspaceImpl entity) throws Exception {
            return notFoundAsTrue(() -> workspaceDao.get(entity.getId()));
        }
    }

    public static class SnapshotMigration extends Migration<SnapshotImpl> {
        private final SnapshotDao snapshotDao;

        public SnapshotMigration(LocalStorage localStorage, SnapshotDao snapshotDao) {
            super("Snapshot", localStorage);
            this.snapshotDao = snapshotDao;
        }

        @Override
        public List<SnapshotImpl> getAllEntities() throws Exception {
            return new ArrayList<>(storage.loadMap(new TypeToken<Map<String, SnapshotImpl>>() {}).values());
        }

        @Override
        public void migrate(SnapshotImpl entity) throws Exception {
            snapshotDao.saveSnapshot(entity);
        }

        @Override
        public boolean isMigrated(SnapshotImpl entity) throws Exception {
            return notFoundAsTrue(() -> snapshotDao.getSnapshot(entity.getId()));
        }
    }

    public static class RecipeMigration extends Migration<RecipeImpl> {

        private final RecipeDao recipeDao;

        public RecipeMigration(LocalStorage localStorage, RecipeDao recipeDao) {
            super("Recipe", localStorage);
            this.recipeDao = recipeDao;
        }

        @Override
        public List<RecipeImpl> getAllEntities() throws Exception {
            return new ArrayList<>(storage.loadMap(new TypeToken<Map<String, RecipeImpl>>() {}).values());
        }

        @Override
        public void migrate(RecipeImpl entity) throws Exception {
            recipeDao.create(entity);
        }

        @Override
        public boolean isMigrated(RecipeImpl entity) throws Exception {
            return notFoundAsTrue(() -> recipeDao.getById(entity.getId()));
        }
    }

    public static class StackMigration extends Migration<StackImpl> {

        private final StackDao stackDao;

        public StackMigration(LocalStorage localStorage, StackDao stackDao) {
            super("Stack", localStorage);
            this.stackDao = stackDao;
        }

        @Override
        public List<StackImpl> getAllEntities() throws Exception {
            return new ArrayList<>(storage.loadMap(new TypeToken<Map<String, StackImpl>>() {}).values());
        }

        @Override
        public void migrate(StackImpl entity) throws Exception {
            stackDao.create(entity);
        }

        @Override
        public boolean isMigrated(StackImpl entity) throws Exception {
            return notFoundAsTrue(() -> stackDao.getById(entity.getId()));
        }
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
