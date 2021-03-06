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
package org.eclipse.che.plugin.docker.machine.proxy;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

/**
 * Add env variable to docker environment with https proxy settings.
 *
 * @author Mykola Morhun
 */
public class HttpsProxyEnvVariableProvider implements Provider<String> {

    private static final String HTTPS_PROXY = "https_proxy=";

    @Inject
    @Named("https.proxy")
    private String httpsProxy;

    @Override
    public String get() {
        return httpsProxy.isEmpty() ? "" : HTTPS_PROXY + httpsProxy;
    }

}
