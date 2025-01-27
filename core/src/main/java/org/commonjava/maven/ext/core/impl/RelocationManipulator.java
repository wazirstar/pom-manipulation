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
package org.commonjava.maven.ext.core.impl;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.model.ConfigurationContainer;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Profile;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectVersionRef;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.common.model.SimpleScopedArtifactRef;
import org.commonjava.maven.ext.common.util.ProfileUtils;
import org.commonjava.maven.ext.common.util.WildcardMap;
import org.commonjava.maven.ext.core.ManipulationSession;
import org.commonjava.maven.ext.core.state.RelocationState;
import org.commonjava.maven.ext.core.state.State;
import org.commonjava.maven.ext.core.util.PropertiesUtils;
import org.commonjava.maven.ext.io.resolver.GalleyAPIWrapper;
import org.commonjava.maven.galley.maven.parse.GalleyMavenXMLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.apache.commons.lang.StringUtils.isEmpty;

/**
 * {@link Manipulator} implementation that can relocation specified groupIds. It will also handle version changes by
 * delegating to dependencyExclusions.
 * Configuration is stored in a {@link RelocationState} instance, which is in turn stored in the {@link ManipulationSession}.
 */
@Named("relocations-manipulator")
@Singleton
public class RelocationManipulator
                implements Manipulator
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final GalleyAPIWrapper galleyWrapper;

    private ManipulationSession session;


    @Inject
    public RelocationManipulator( GalleyAPIWrapper galleyWrapper )
    {
        this.galleyWrapper = galleyWrapper;
    }

    /**
     * Initialize the {@link RelocationState} state holder in the {@link ManipulationSession}. This state holder detects
     * relocation configuration from the Maven user properties (-D properties from the CLI) and makes it available for
     * later.
     */
    @Override
    public void init( final ManipulationSession session )
                    throws ManipulationException
    {
        this.session = session;
        session.setState( new RelocationState( session.getUserProperties() ) );
    }

    /**
     * Apply the relocation changes to the list of {@link Project}'s given.
     */
    @Override
    public Set<Project> applyChanges( final List<Project> projects )
                    throws ManipulationException
    {
        final State state = session.getState( RelocationState.class );

        if ( !session.isEnabled() || !state.isEnabled() )
        {
            logger.debug( "{}: Nothing to do!", getClass().getSimpleName() );
            return Collections.emptySet();
        }

        final Set<Project> changed = new HashSet<>();

        for ( final Project project : projects )
        {
            final Model model = project.getModel();

            if ( apply( project, model ) )
            {
                changed.add( project );
            }
        }

        return changed;
    }

    private boolean apply( final Project project, final Model model ) throws ManipulationException
    {
        boolean result = false;
        final RelocationState state = session.getState( RelocationState.class );
        final WildcardMap<ProjectVersionRef> dependencyRelocations = state.getDependencyRelocations();
        final WildcardMap<ProjectVersionRef> pluginRelocations = state.getPluginRelocations();

        logger.debug( "Applying relocation changes for dependencies ({}) and for plugins ({}) to: {}:{}",
                      dependencyRelocations, pluginRelocations,
                      project.getGroupId(), project.getArtifactId() );

        DependencyManagement dependencyManagement = model.getDependencyManagement();
        if ( dependencyManagement != null )
        {
            result = updateDependencies( project, dependencyRelocations, project.getResolvedManagedDependencies( session ) );
        }
        result |= updateDependencies( project, dependencyRelocations, project.getAllResolvedDependencies( session ) );

        for ( final Profile profile : ProfileUtils.getProfiles( session, model) )
        {
            dependencyManagement = profile.getDependencyManagement();
            if ( dependencyManagement != null )
            {
                result |= updateDependencies( project, dependencyRelocations, project.getResolvedProfileManagedDependencies( session ).get( profile ) );
            }
            result |= updateDependencies( project, dependencyRelocations, project.getAllResolvedProfileDependencies( session ).get( profile ) );

        }

        result |= updatePlugins( pluginRelocations, dependencyRelocations, project, project.getResolvedManagedPlugins( session ) );

        result |= updatePlugins( pluginRelocations, dependencyRelocations, project, project.getAllResolvedPlugins( session ) );

        for ( Profile profile : project.getAllResolvedProfilePlugins( session ).keySet() )
        {
            result |= updatePlugins( pluginRelocations, dependencyRelocations, project, project.getAllResolvedProfilePlugins( session ).get( profile ) );
        }

        for ( Profile profile : project.getResolvedProfileManagedPlugins( session ).keySet() )
        {
            result |= updatePlugins( pluginRelocations, dependencyRelocations, project, project.getResolvedProfileManagedPlugins( session ).get( profile ) );
        }

        return result;
    }

    private boolean updateDependencies( Project project, WildcardMap<ProjectVersionRef> relocations, Map<ArtifactRef, Dependency> dependencies )
                    throws ManipulationException
    {
        final Map<ArtifactRef, Dependency> postFixUp = new HashMap<>();
        boolean result = false;

        // If we do a single pass over the dependencies that will handle the relocations *but* it will not handle
        // where one relocation alters the dependency and a subsequent relocation alters it again. For instance, the
        // first might wildcard alter the groupId and the second, more specifically alters one with the artifactId
        for ( int i = 0 ; i < relocations.size(); i++ )
        {
            Iterator<ArtifactRef> it = dependencies.keySet().iterator();
            while ( it.hasNext() )
            {
                final ArtifactRef pvr = it.next();
                if ( relocations.containsKey( pvr.asProjectRef() ) )
                {
                    ProjectVersionRef relocation = relocations.get( pvr.asProjectRef() );
                    Dependency dependency = dependencies.get( pvr );

                    logger.info( "For dependency {}, replacing groupId {} by {} and artifactId {} with {}", dependency,
                                 dependency.getGroupId(), relocation.getGroupId(),
                                 dependency.getArtifactId(), relocation.getArtifactId() );

                    if ( !relocation.getArtifactId().equals( WildcardMap.WILDCARD ) )
                    {
                        updateString( project, dependency.getArtifactId(), relocation, relocation.getArtifactId(),
                                      d -> dependency.setArtifactId( relocation.getArtifactId() ) );
                    }
                    if (relocation.getVersionString().equals( WildcardMap.WILDCARD ) )
                    {
                        logger.debug ("No version alignment to perform for relocation {}", relocation);
                    }
                    else
                    {
                        updateString( project, dependency.getVersion(),
                                      relocation, relocation.getVersionString(),
                                      d -> dependency.setVersion( relocation.getVersionString() ) );
                    }

                    updateString( project, dependency.getGroupId(), relocation, relocation.getGroupId(),
                                  d -> dependency.setGroupId( relocation.getGroupId() ) );

                    // Unfortunately because we iterate using the resolved project keys if the relocation updates those
                    // keys multiple iterations will not work. Therefore we need to remove the original key:dependency
                    // to map to the relocated form.
                    postFixUp.put( new SimpleScopedArtifactRef( dependency ), dependency );
                    it.remove();

                    result = true;
                }
            }
            dependencies.putAll( postFixUp );
            postFixUp.clear();
        }
        return result;
    }

    private boolean updatePlugins( WildcardMap<ProjectVersionRef> pluginRelocations, final WildcardMap<ProjectVersionRef> dependencyRelocations, final Project project,
                                   final Map<ProjectVersionRef, Plugin> pluginMap ) throws ManipulationException
    {
        final Map<ProjectVersionRef, Plugin> postFixUp = new HashMap<>();
        boolean result = false;

        // Handles plugin configurations
        final List<PluginReference> refs = findPluginReferences( project, pluginMap );
        final int size = dependencyRelocations.size();

        for ( int i = 0; i < size; i++ )
        {
            for ( PluginReference pluginReference : refs )
            {
                final Dependency dependency = new Dependency();

                dependency.setGroupId( pluginReference.groupIdNode.getTextContent() );
                dependency.setArtifactId( pluginReference.artifactIdNode.getTextContent() );

                final ProjectVersionRef relocation = dependencyRelocations.get( dependency );

                if ( relocation != null )
                {
                    updateString( project, pluginReference.groupIdNode.getTextContent(), relocation, relocation.getGroupId(),
                                  d -> pluginReference.groupIdNode.setTextContent( relocation.getGroupId() ) );

                    if ( !relocation.getArtifactId().equals( WildcardMap.WILDCARD ) )
                    {
                        updateString( project, pluginReference.artifactIdNode.getTextContent(), relocation, relocation.getArtifactId(),
                                      d -> pluginReference.artifactIdNode.setTextContent( relocation.getArtifactId() ) );
                    }

                    if ( pluginReference.versionNode != null)
                    {
                        if ( relocation.getVersionString().equals( WildcardMap.WILDCARD ) )
                        {
                            logger.debug ("No version alignment to perform for relocation {}", relocation);
                        }
                        else
                        {
                            updateString( project, pluginReference.versionNode.getTextContent(), relocation, relocation.getVersionString(),
                                          d -> pluginReference.versionNode.setTextContent( relocation.getVersionString() ) );
                        }
                    }
                    pluginReference.container.setConfiguration( getConfigXml( pluginReference.groupIdNode ) );

                    logger.debug( "Update plugin: set {} to {}", relocation, pluginReference );

                    result = true;
                }
            }
        }

        // Handles plugins themselves
        for ( int i = 0 ; i < pluginRelocations.size(); i++ )
        {
            Iterator<ProjectVersionRef> it = pluginMap.keySet().iterator();
            while ( it.hasNext() )
            {
                final ProjectVersionRef pvr = it.next();

                if ( pluginRelocations.containsKey( pvr.asProjectRef() ) )
                {
                    Plugin plugin = pluginMap.get( pvr );
                    ProjectVersionRef relocation = pluginRelocations.get( pvr.asProjectRef() );

                    logger.info( "For plugin {}, replacing groupId {} by {} and artifactId {} with {}", plugin.getId(),
                                 plugin.getGroupId(), relocation.getGroupId(), plugin.getArtifactId(), relocation.getArtifactId() );

                    if ( !relocation.getArtifactId().equals( WildcardMap.WILDCARD ) )
                    {
                        updateString( project, plugin.getArtifactId(), relocation, relocation.getArtifactId(), d -> plugin.setArtifactId( relocation.getArtifactId() ) );
                    }
                    if ( relocation.getVersionString().equals( WildcardMap.WILDCARD ) )
                    {
                        logger.debug( "No version alignment to perform for relocation {}", relocation );
                    }
                    else
                    {
                        updateString( project, plugin.getVersion(), relocation, relocation.getVersionString(), d -> plugin.setVersion( relocation.getVersionString() ) );
                    }

                    updateString( project, plugin.getGroupId(), relocation, relocation.getGroupId(), d -> plugin.setGroupId( relocation.getGroupId() ) );

                    postFixUp.put( new SimpleProjectVersionRef( plugin.getGroupId(), plugin.getArtifactId(),
                                                                isEmpty( plugin.getVersion() ) ? "*" : plugin.getVersion()), plugin );
                    it.remove();
                    result = true;
                }
            }
            pluginMap.putAll( postFixUp );
            postFixUp.clear();
        }

        return result;
    }

    /**
     * This handles updating a value (be it from a Dependency or Plugin) e.g. {@code artifactId} to its new value taking into
     * account if there are any properties.
     *
     * @param project a references to the Maven Project
     * @param originalValue the original property value e.g. the value of {@code artifactId}
     * @param originalRelocation the complete original relocation
     * @param relocation the portion of the relocation that is currently being processed
     * @param c a Consumer function that will update the corresponding Dependency/Plugin if its not a property.
     * @throws ManipulationException if an error occurs.
     */
    private void updateString( Project project, String originalValue, ProjectVersionRef originalRelocation, String relocation,
                               Consumer<String> c ) throws ManipulationException
    {
        if ( StringUtils.contains( originalValue, "$" ))
        {
            originalValue = originalValue.substring( 2, originalValue.length() - 1 );
            if ( StringUtils.countMatches( originalValue, "${" ) > 1 )
            {
                throw new ManipulationException( "Relocations with multiple embedded version properties not supported" );
            }
            logger.debug( "Updating relocation for {} with property {} to new value {}", originalRelocation, originalValue, relocation );
            PropertiesUtils.updateProperties( session, project, true, originalValue, relocation );
        }
        else
        {
            c.accept( relocation );
        }
    }

    private PluginReference findPluginReference( final ConfigurationContainer container, final Node parent )
    {
        if ( parent == null )
        {
            return null;
        }

        final NodeList children = parent.getChildNodes();
        final int length = children.getLength();

        logger.debug( "Update child nodes for {} with {} children", parent.getNodeName(), length );

        Node groupIdNode = null;
        Node artifactIdNode = null;
        Node versionNode = null;

        for ( int i = 0; i < length; i++ )
        {
            final Node node = children.item( i );

            switch ( node.getNodeName() )
            {
                case "groupId":
                    groupIdNode = node;
                    break;
                case "artifactId":
                    artifactIdNode = node;
                    break;
                case "version":
                    versionNode = node;
                    break;
            }
        }

        if ( groupIdNode != null && artifactIdNode != null )
        {
            PluginReference ref = new PluginReference( container, groupIdNode, artifactIdNode, versionNode );

            logger.debug( "Found plugin reference: {}", ref );

            return ref;
        }

        return null;
    }

    private List<PluginReference> findPluginReferences( final Map.Entry<ConfigurationContainer, String> entry, final NodeList children )
    {
        final int length = children.getLength();

        logger.debug( "Got {} children to update plugin GAVs", length );

        final List<PluginReference> refs = new ArrayList<>( length );

        for ( int i = 0; i < length; i++ )
        {
            final Node node = children.item( i );

            logger.debug( "Child name is {} and text content is {}", node.getNodeName(), node.getTextContent() );

            final PluginReference ref = findPluginReference( entry.getKey(), node );

            if ( ref != null )
            {
                refs.add( ref );
            }
        }

        return refs;
    }

    private List<PluginReference> findPluginReferences( final Project project, final Map<ProjectVersionRef, Plugin> pluginMap ) throws ManipulationException
    {
        final Collection<Plugin> plugins = pluginMap.values();
        final List<PluginReference> refs = new ArrayList<>();

        for ( final Plugin plugin : plugins )
        {
            final Map<ConfigurationContainer, String> configs = findConfigurations( plugin );

            logger.debug( "Found {} configs for plugin {}:{}:{}", configs.size(), plugin.getGroupId(), plugin.getArtifactId(), plugin.getVersion() );

            for ( final Map.Entry<ConfigurationContainer, String> entry : configs.entrySet() )
            {
                try
                {
                    final Document doc = galleyWrapper.parseXml( entry.getValue() );
                    final XPath xPath = XPathFactory.newInstance().newXPath();
                    final PluginReference ref = findPluginReference( entry.getKey(), doc.getFirstChild() );

                    if ( ref !=  null )
                    {
                        refs.add( ref );
                    }

                    final NodeList artifactItems = (NodeList) xPath.evaluate( ".//artifactItems/*", doc, XPathConstants.NODESET );
                    final List<PluginReference> artifactItemRefs = findPluginReferences( entry, artifactItems );

                    refs.addAll( artifactItemRefs );
                }
                catch ( final GalleyMavenXMLException e )
                {
                    throw new ManipulationException( "Unable to parse config for plugin {} in {}", plugin.getId(), project.getKey(), e );
                }
                catch ( final  XPathExpressionException e )
                {
                    throw new ManipulationException( "Invalid XPath expression for plugin {} in {}", plugin.getId(), project.getKey(), e );
                }
            }
        }

        return refs;
    }

    private Map<ConfigurationContainer, String> findConfigurations( final Plugin plugin )
    {
        if ( plugin == null )
        {
            return Collections.emptyMap();
        }

        final Map<ConfigurationContainer, String> configs = new LinkedHashMap<>();
        final Object pluginConfiguration = plugin.getConfiguration();

        if ( pluginConfiguration != null )
        {
            configs.put( plugin, pluginConfiguration.toString() );
        }

        final List<PluginExecution> executions = plugin.getExecutions();

        if ( executions != null )
        {
            for ( PluginExecution execution : executions )
            {
                final Object executionConfiguration = execution.getConfiguration();

                if ( executionConfiguration != null )
                {
                    configs.put( execution, executionConfiguration.toString() );
                }
            }
        }

        return configs;
    }

    private Xpp3Dom getConfigXml( final Node node ) throws ManipulationException
    {
        final String config = galleyWrapper.toXML( node.getOwnerDocument(), false ).trim();

        try
        {
            return Xpp3DomBuilder.build( new StringReader( config ) );
        }
        catch ( final XmlPullParserException | IOException e )
        {
            throw new ManipulationException( "Failed to re-parse plugin configuration into Xpp3Dom: {}. Config was: {}", e.getMessage(), config, e );
        }
    }

    @Override
    public int getExecutionIndex()
    {
        return 7;
    }

    private static final class PluginReference
    {
        private final ConfigurationContainer container;

        private final Node groupIdNode;

        private final Node artifactIdNode;

        private final Node versionNode;

        PluginReference( final ConfigurationContainer container, final Node groupIdNode, final Node artifactIdNode,
                         final Node versionNode )
        {
            this.container = container;
            this.groupIdNode = groupIdNode;
            this.artifactIdNode = artifactIdNode;
            this.versionNode = versionNode;
        }

        @Override
        public String toString()
        {
            return "PluginReference{" + "container=" + container + nodeToString(groupIdNode) + nodeToString(artifactIdNode) + nodeToString(versionNode) + '}';
        }

        private String nodeToString(Node node)
        {
            return node == null ? "" : ", [" + node.getNodeName() + "=" + node.getTextContent() + "]";
        }
    }
}
