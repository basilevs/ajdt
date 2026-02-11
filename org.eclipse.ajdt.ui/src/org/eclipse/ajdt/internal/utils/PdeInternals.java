/*******************************************************************************
 * Copyright (c) 2025 Xored Software Inc and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *     Xored Software Inc - initial API and implementation and/or initial documentation
 *******************************************************************************/
package org.eclipse.ajdt.internal.utils;

import org.eclipse.core.resources.IProject;

public final class PdeInternals {
    private PdeInternals() {}
    public static boolean isPluginProject(IProject project) {
    	return org.eclipse.pde.internal.core.natures.PluginProject.isPluginProject(project);
    }
}
