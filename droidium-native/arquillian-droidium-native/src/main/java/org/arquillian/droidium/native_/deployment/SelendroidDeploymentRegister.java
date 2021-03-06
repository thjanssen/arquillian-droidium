/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.arquillian.droidium.native_.deployment;

import org.arquillian.droidium.container.deployment.DeploymentRegister;
import org.arquillian.droidium.native_.spi.SelendroidDeployment;

/**
 *
 * @author <a href="mailto:smikloso@redhat.com">Stefan Miklosovic</a>
 *
 */
public class SelendroidDeploymentRegister extends DeploymentRegister<SelendroidDeployment> {

    @Override
    public SelendroidDeployment get(String deploymentName) {
        // reverse order, corner case for 2 method scoped drivers, each
        // in one method, without qualifiers, instrumenting the same deployment
        for (int i = getAll().size() - 1; i >= 0; i--) {
            if (get(i).getDeploymentName().equals(deploymentName)) {
                return get(i);
            }
        }
        return null;
    }

}
