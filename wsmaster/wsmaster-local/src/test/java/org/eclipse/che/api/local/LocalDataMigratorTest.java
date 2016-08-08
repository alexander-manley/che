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

import org.eclipse.che.api.local.storage.LocalStorageFactory;
import org.eclipse.che.api.local.storage.stack.StackLocalStorage;
import org.eclipse.che.api.machine.server.model.impl.SnapshotImpl;
import org.eclipse.che.api.machine.server.recipe.RecipeImpl;
import org.eclipse.che.api.machine.server.spi.RecipeDao;
import org.eclipse.che.api.machine.server.spi.SnapshotDao;
import org.eclipse.che.api.ssh.server.model.impl.SshPairImpl;
import org.eclipse.che.api.ssh.server.spi.SshDao;
import org.eclipse.che.api.user.server.model.impl.ProfileImpl;
import org.eclipse.che.api.user.server.model.impl.UserImpl;
import org.eclipse.che.api.user.server.spi.PreferenceDao;
import org.eclipse.che.api.user.server.spi.ProfileDao;
import org.eclipse.che.api.user.server.spi.UserDao;
import org.eclipse.che.api.workspace.server.model.impl.WorkspaceConfigImpl;
import org.eclipse.che.api.workspace.server.model.impl.WorkspaceImpl;
import org.eclipse.che.api.workspace.server.model.impl.stack.StackImpl;
import org.eclipse.che.api.workspace.server.spi.StackDao;
import org.eclipse.che.api.workspace.server.spi.WorkspaceDao;
import org.eclipse.che.commons.lang.IoUtil;
import org.mockito.Mock;
import org.mockito.testng.MockitoTestNGListener;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Tests {@link LocalDataMigrator}.
 *
 * @author Yevhenii Voevodin
 */
@Listeners(MockitoTestNGListener.class)
public class LocalDataMigratorTest {

    private Path                baseDir;
    private LocalStorageFactory factory;

    @Mock
    private UserDao userDao;

    @Mock
    private ProfileDao profileDao;

    @Mock
    private PreferenceDao preferenceDao;

    @Mock
    private SshDao sshDao;

    @Mock
    private WorkspaceDao workspaceDao;

    @Mock
    private SnapshotDao snapshotDao;

    @Mock
    private RecipeDao recipeDao;

    @Mock
    private StackDao stackDao;

    private LocalDataMigrator dataMigrator;

    @BeforeMethod
    private void setUp() throws IOException {
        baseDir = Files.createTempDirectory(Paths.get("/tmp"), "test");
        factory = new LocalStorageFactory(baseDir.toString());
        dataMigrator = new LocalDataMigrator();
    }

    @AfterMethod
    private void cleanUp() {
        IoUtil.deleteRecursive(baseDir.toFile());
    }

    @Test(dataProvider = "migrationActions")
    public void shouldMigrateLocalData(String fileName, TestAction saveLocalEntity, TestAction verification) throws Exception {
        saveLocalEntity.perform();

        dataMigrator.performMigration(baseDir.toString(),
                                      userDao,
                                      profileDao,
                                      preferenceDao,
                                      sshDao,
                                      workspaceDao,
                                      snapshotDao,
                                      recipeDao,
                                      stackDao);

        verification.perform();
        assertFalse(Files.exists(baseDir.resolve(fileName)));
        assertTrue(Files.exists(baseDir.resolve(fileName + ".backup")));
    }

    @DataProvider(name = "migrationActions")
    public Object[][] migrationActions() {
        final UserImpl user = new UserImpl("id", "email", "name");
        final ProfileImpl profile = new ProfileImpl(user.getId());
        final Map<String, String> prefs = singletonMap("key", "value");
        final SshPairImpl sshPair = new SshPairImpl(user.getId(), "service", "name", "public", "private");
        final WorkspaceImpl workspace = new WorkspaceImpl("id", "namespace", new WorkspaceConfigImpl());
        final SnapshotImpl snapshot = new SnapshotImpl();
        snapshot.setId("snapshotId");
        snapshot.setWorkspaceId(workspace.getId());
        final RecipeImpl recipe = new RecipeImpl();
        recipe.setId("id");
        recipe.setCreator(user.getId());
        final StackImpl stack = new StackImpl();
        stack.setId("id");
        stack.setName("name");
        return new Object[][] {
                {
                        LocalUserDaoImpl.FILENAME,
                        (TestAction)() -> factory.create(LocalUserDaoImpl.FILENAME).store(singletonMap(user.getId(), user)),
                        (TestAction)() -> verify(userDao).create(user)
                },
                {
                        LocalProfileDaoImpl.FILENAME,
                        (TestAction)() -> factory.create(LocalProfileDaoImpl.FILENAME).store(singletonMap(profile.getUserId(), profile)),
                        (TestAction)() -> verify(profileDao).create(profile)
                },
                {
                        LocalPreferenceDaoImpl.FILENAME,
                        (TestAction)() -> factory.create(LocalPreferenceDaoImpl.FILENAME).store(singletonMap(user.getId(), prefs)),
                        (TestAction)() -> verify(preferenceDao).setPreferences(user.getId(), prefs)
                },
                {
                        LocalSshDaoImpl.FILENAME,
                        (TestAction)() -> factory.create(LocalSshDaoImpl.FILENAME).store(singletonList(sshPair)),
                        (TestAction)() -> verify(sshDao).create(sshPair)
                },
                {
                        LocalWorkspaceDaoImpl.FILENAME,
                        (TestAction)() -> factory.create(LocalWorkspaceDaoImpl.FILENAME).store(singletonMap(workspace.getId(), workspace)),
                        (TestAction)() -> verify(workspaceDao).create(workspace)
                },
                {
                        LocalSnapshotDaoImpl.FILENAME,
                        (TestAction)() -> factory.create(LocalSnapshotDaoImpl.FILENAME).store(singletonMap(snapshot.getId(), snapshot)),
                        (TestAction)() -> verify(snapshotDao).saveSnapshot(snapshot)
                },
                {
                        LocalRecipeDaoImpl.FILENAME,
                        (TestAction)() -> factory.create(LocalRecipeDaoImpl.FILENAME).store(singletonMap(recipe.getId(), recipe)),
                        (TestAction)() -> verify(recipeDao).create(recipe)
                },
                {
                        StackLocalStorage.STACK_STORAGE_FILE,
                        (TestAction)() -> factory.create(StackLocalStorage.STACK_STORAGE_FILE).store(singletonMap(stack.getId(), stack)),
                        (TestAction)() -> verify(stackDao).create(stack)
                }
        };
    }

    @FunctionalInterface
    private interface TestAction {
        void perform() throws Exception;
    }
}
