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

import org.eclipse.che.api.local.storage.LocalStorageFactory;
import org.eclipse.che.api.user.server.spi.UserDao;
import org.eclipse.che.commons.lang.IoUtil;
import org.mockito.Mock;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;

/**
 * Tests {@link LocalDataMigrator}.
 *
 * @author Yevhenii Voevodin
 */
public class LocalDataMigratorTest {

    private File                baseDir;
    private LocalStorageFactory factory;

    @Mock
    private UserDao userDao;

    @BeforeMethod
    private void setUp() {
        baseDir = Files.createTempDir();
        factory = new LocalStorageFactory(baseDir.getAbsolutePath());
    }

    @AfterMethod
    private void cleanUp() {
        IoUtil.deleteRecursive(baseDir);
    }

    // TODO

    @Test
    public void shouldMigrateLocalData() throws Exception {
        final LocalDataMigrator dataMigrator = new LocalDataMigrator();

        dataMigrator.performMigration(baseDir.getAbsolutePath(), userDao);
    }
}
