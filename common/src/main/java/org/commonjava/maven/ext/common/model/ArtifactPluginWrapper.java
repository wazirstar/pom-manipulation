/*
 * Copyright (C) 2012 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.maven.ext.common.model;

import lombok.Getter;
import org.apache.maven.model.Plugin;
import org.commonjava.maven.atlas.ident.ref.SimpleArtifactRef;

/**
 * Simple wrapper to allow a Plugin to be treated as an ArtifactRef which allows for more generic code.
 */
public class ArtifactPluginWrapper extends SimpleArtifactRef
{
    @Getter
    private final Plugin original;

    public ArtifactPluginWrapper( Plugin p )
    {
        super (p.getGroupId(), p.getArtifactId(), p.getVersion(), null, null);
        this.original = p;
    }

    @Override
    public String toString()
    {
        return getClass() + " : " + super.toString();
    }
}