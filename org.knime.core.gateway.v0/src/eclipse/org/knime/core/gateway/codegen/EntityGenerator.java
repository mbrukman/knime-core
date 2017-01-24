/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME GmbH, Konstanz, Germany
 *  Website: http://www.knime.org; Email: contact@knime.org
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME GMBH herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   Nov 30, 2016 (hornm): created
 */
package org.knime.core.gateway.codegen;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.knime.core.gateway.codegen.types.EntityDef;
import org.knime.core.gateway.codegen.types.EntityField;
import org.knime.core.node.util.CheckUtils;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

/**
 *
 * @author Martin Horn, University of Konstanz
 */
public final class EntityGenerator {

    private String m_templateFile;

    private String m_destDirectory;

    private String m_destFileName;

    /**
     * @param templateFile the template file
     * @param destDirectory the destination directory for the generated java-files
     * @param destFileName the name of the destination file. The placeholder "##entityName##" will be replaced with the
     *            actual entity name.
     *
     */
    public EntityGenerator(final String templateFile, final String destDirectory, final String destFileName) {
        m_templateFile = templateFile;
        m_destDirectory = destDirectory;
        m_destFileName = destFileName;
    }

    public void generate() {

        try {
            Velocity.init();
            VelocityContext context = new VelocityContext();
            List<EntityDef> entityDefs = getEntityDefs();

            Map<String, EntityDef> entitiyDefMap = new HashMap<String, EntityDef>();
            for(EntityDef entityDef : entityDefs) {
                entitiyDefMap.put(entityDef.getPackage() + "." + entityDef.getName(), entityDef);
            }

            for (EntityDef entityDef : entityDefs) {
                context.put("name", entityDef.getName());
                context.put("classDescription", entityDef.getDescription());
                context.put(StringUtils.class.getSimpleName(), StringUtils.class);

                List<EntityField> fields = new ArrayList<EntityField>(entityDef.getFields());
                List<String> imports = new ArrayList<String>(entityDef.getImports());
                List<String> superClasses = new ArrayList<String>();

                // add fields and imports from other entities, too
                entityDef.getParentOptional().ifPresent(extendsFrom -> {
                    EntityDef superEntity = entitiyDefMap.get(extendsFrom);
                    CheckUtils.checkArgumentNotNull(superEntity, "No parent \"%s\" for child entity \"%s\"", extendsFrom, entityDef.getName());
                    fields.addAll(superEntity.getFields());
                    imports.addAll(superEntity.getImports());
                    superClasses.add(extendsFrom);
                });

                context.put("fields", fields);
                context.put("imports", imports);
                context.put("superClasses", superClasses);

                Template template = null;
                try {
                    template = Velocity.getTemplate(m_templateFile);
                } catch (ResourceNotFoundException rnfe) {
                    System.out.println("Example : error : cannot find template " + m_templateFile);
                } catch (ParseErrorException pee) {
                    System.out.println("Example : Syntax error in template " + m_templateFile + ":" + pee);
                }

                String destFileName = m_destFileName.replace("##entityName##", entityDef.getName());

                /*
                 *  Now have the template engine process your template using the
                 *  data placed into the context.  Think of it as a  'merge'
                 *  of the template and the data to produce the output stream.
                 */
                String[] packages = entityDef.getPackage().split("\\.");
                Path parentPath = Paths.get("api", packages);
                Files.createDirectories(parentPath);

                FileWriter fileWriter = new FileWriter(m_destDirectory + destFileName + ".java");
                Path path = parentPath.resolve(destFileName + ".json");
                try (BufferedWriter writer = Files.newBufferedWriter(path)) {
                    ObjectMapper mapper = new ObjectMapper();
                    mapper.registerModule(new Jdk8Module());
                    mapper.setSerializationInclusion(Include.NON_NULL);
                    mapper.enable(SerializationFeature.INDENT_OUTPUT);
                    // 4 space indentation
                    DefaultPrettyPrinter.Indenter indenter = new DefaultIndenter("    ", DefaultIndenter.SYS_LF);
                    DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
                    printer.indentObjectsWith(indenter);
                    printer.indentArraysWith(indenter);
                    mapper.writer(printer).writeValue(writer, entityDef);
                    EntityDef def1 = mapper.readValue(Files.newBufferedReader(path), EntityDef.class);
                }


                BufferedWriter writer = new BufferedWriter(fileWriter);

                if (template != null) {
                    template.merge(context, writer);
                }

                /*
                 *  flush and cleanup
                 */

                writer.flush();
                writer.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static List<EntityDef> getEntityDefs() {
        //TODO: e.g. read from a json file

        return Arrays.asList(
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "NodeEnt",
                "The individual node.",
                new EntityField("Parent", "The parent of the node.", "org.knime.core.gateway.v0.workflow.entity.EntityID"),
                new EntityField("JobManager", "The job manager (e.g. cluster or streaming).", "org.knime.core.gateway.v0.workflow.entity.JobManagerEnt"),
                new EntityField("NodeMessage", "The current node message (warning, error, none).", "org.knime.core.gateway.v0.workflow.entity.NodeMessageEnt"),
                new EntityField("InPorts", "The list of inputs.", "List<org.knime.core.gateway.v0.workflow.entity.NodeInPortEnt>"),
                new EntityField("OutPorts", "The list of outputs.", "List<org.knime.core.gateway.v0.workflow.entity.NodeOutPortEnt>"),
                new EntityField("Name", "The name.", "String"),
                new EntityField("NodeID", "The ID of the node.", "String"),
                new EntityField("NodeTypeID", "The ID of the node type (metanode, native nodes, etc).", "String"),
                new EntityField("NodeType", "The type of the node as string.", "String"),
                new EntityField("Bounds", "The bounds / rectangle on screen of the node.", "org.knime.core.gateway.v0.workflow.entity.BoundsEnt"),
                new EntityField("IsDeletable", "Whether node is deletable.", "boolean"),
                new EntityField("NodeState", "The state of the node.", "String"),
                new EntityField("HasDialog", "Whether the node has a configuration dialog / user settings.", "boolean"),
                new EntityField("NodeAnnotation", "The annotation underneath the node.", "org.knime.core.gateway.v0.workflow.entity.NodeAnnotationEnt")),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "NativeNodeEnt", "Native node extension of a NodeEnt",
                new EntityField("NodeFactoryID", "The ID of the node factory defining all details.", "org.knime.core.gateway.v0.workflow.entity.NodeFactoryIDEnt"))
                .setParent("org.knime.core.gateway.v0.workflow.entity.NodeEnt"),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "NodeFactoryIDEnt",
                "Details on a single node implementation",
                new EntityField("ClassName", "The fully qualified java classname", "String"),
                new EntityField("NodeName", "The static name of the node as appears on the screen.", "String")),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "ConnectionEnt",
                "A single connection between two nodes.",
                new EntityField("Dest", "The destination node", "String"),
                new EntityField("DestPort", "The destination port, starting at 0", "int"),
                new EntityField("Source", "The source node.", "String"),
                new EntityField("SourcePort", "The source port, starting at 0.", "int"),
                new EntityField("IsDeleteable", "Whether the connection can currently be deleted.", "boolean"),
                new EntityField("BendPoints", "The list of handles/bend points.", "List<org.knime.core.gateway.v0.workflow.entity.XYEnt>"),
                new EntityField("Type", "The type of the connection (standard, workflow input / output / through).", "String")),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "NodePortEnt",
                "A single port to a node.",
                new EntityField("PortIndex", "The index starting at 0.", "int"),
                new EntityField("PortType", "The type of the port.", "org.knime.core.gateway.v0.workflow.entity.PortTypeEnt"),
                new EntityField("PortName", "The name of the port.", "String")),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity", "NodeInPortEnt",
                "An input port of a node.").setParent("org.knime.core.gateway.v0.workflow.entity.NodePortEnt"),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity", "NodeOutPortEnt",
                "An output port of a node.").setParent("org.knime.core.gateway.v0.workflow.entity.NodePortEnt"),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "PortTypeEnt",
                "The type of a port.",
                new EntityField("Name", "Port type name.", "String"),
                new EntityField("PortObjectClassName", "Port type class name (for coloring, connection checks).", "String"),
                new EntityField("IsOptional", "Whether the port is optional, only applies to input ports", "boolean"),
                new EntityField("Color", "The color of a port.", "int"),
                new EntityField("IsHidden", "Whether the port is hidden (flow variable in/output).", "boolean")),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "NodeMessageEnt",
                "A node message.",
                new EntityField("Message", "The message string itself.", "String"),
                new EntityField("Type", "The type of message (warning, error).", "String")),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "JobManagerEnt",
                "The job manager of a node.",
                new EntityField("Name", "Name of manager.", "String"),
                new EntityField("JobManagerID", "ID of manager implementation.", "String")),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "BoundsEnt",
                "Node dimension -- position and size.",
                new EntityField("X", "X coordinate.", "int"),
                new EntityField("Y", "Y coordinate.", "int"),
                new EntityField("Width", "Width of the widget.", "int"),
                new EntityField("Height", "Height of the widget.", "int")),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "XYEnt",
                "XY coordinate.",
                new EntityField("X", "X coordinate.", "int"),
                new EntityField("Y", "Y coordinate.", "int")),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "WorkflowAnnotationEnt",
                "A workflow annotation.",
                new EntityField("Text", "The text.", "String"),
                new EntityField("Bounds", "Position/Size of an annotation.", "org.knime.core.gateway.v0.workflow.entity.BoundsEnt"),
                new EntityField("BgColor", "Background color.", "int"),
                new EntityField("BorderSize", "Border thickness.", "int"),
                new EntityField("BorderColor", "Border color.", "int"),
                new EntityField("FontSize", "The font fize.", "int"),
                new EntityField("Alignment", "Text alignment.", "String")),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "WorkflowEnt",
                "A complete workflow.",
                new EntityField("Nodes", "The node map.", "Map<String, org.knime.core.gateway.v0.workflow.entity.NodeEnt>"),
                new EntityField("Connections", "The list of connections.", "List<org.knime.core.gateway.v0.workflow.entity.ConnectionEnt>"),
                new EntityField("MetaInPorts", "The inputs of a metanode (if this workflow is one).", "List<org.knime.core.gateway.v0.workflow.entity.MetaPortEnt>"),
                new EntityField("MetaOutPorts", "The outputs of a metanode (if this workflow is one).", "List<org.knime.core.gateway.v0.workflow.entity.MetaPortEnt>"))
                .setParent("org.knime.core.gateway.v0.workflow.entity.NodeEnt"),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "EntityID",
                "The id of a workflow, used for lookups.",
                new EntityField("ID", "The ID string.", "String"),
                new EntityField("Type", "The type.", "String")),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "AnnotationEnt",
                "A text annotation.",
                new EntityField("Text", "The text.", "String"),
                new EntityField("BackgroundColor", "The background color.", "int"),
                new EntityField("X", "The x coordinate.", "int"),
                new EntityField("Y", "The y coordinate.", "int"),
                new EntityField("Width", "The width.", "int"),
                new EntityField("Height", "The height.", "int"),
                new EntityField("TextAlignment", "The text alignment.", "String"),
                new EntityField("BorderSize", "The border width.", "int"),
                new EntityField("BorderColor", "The border color.", "int"),
                new EntityField("DefaultFontSize", "The default font size.", "int"),
                new EntityField("Version", "The version.", "int")),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "NodeAnnotationEnt",
                "The annotation to a noe.",
                new EntityField("Node", "The node to which this annotation is attached.", "String"))
                .setParent("org.knime.core.gateway.v0.workflow.entity.AnnotationEnt"),
            new EntityDef("org.knime.core.gateway.v0.workflow.entity",
                "MetaPortEnt",
                "The port of a metanode.",
                new EntityField("PortType", "The type.", "org.knime.core.gateway.v0.workflow.entity.PortTypeEnt"),
                new EntityField("IsConnected", "Whether it is connected.", "boolean"),
                new EntityField("Message", "The message (summary of upstream node port).", "String"),
                new EntityField("OldIndex", "The old index (@Martin, please clarify?)", "int"),
                new EntityField("NewIndex", "The new index (@Martin, please clarify?).", "int")),
            new EntityDef("org.knime.core.gateway.v0.repository.entity",
                "RepoCategoryEnt",
                "A category in the node, including its children (nodes and categories).",
                new EntityField("Name", "The name/label.", "String"),
                new EntityField("IconURL", "The URL of the icon..", "String"),
                new EntityField("Categories", "Child categories.", "List<org.knime.core.gateway.v0.repository.entity.RepoCategoryEnt>"),
                new EntityField("Nodes", "Nodes in the category.", "List<org.knime.core.gateway.v0.repository.entity.RepoNodeTemplateEnt>")),
            new EntityDef("org.knime.core.gateway.v0.repository.entity",
                "RepoNodeTemplateEnt",
                "A node in the node repository.",
                new EntityField("Name", "The name of the node.", "String"),
                new EntityField("Type", "The type of the node (for background color).", "String"),
                new EntityField("ID", "The ID for later references.", "String"),
                new EntityField("IconURL", "The icon URL.", "String"),
                new EntityField("NodeTypeID", "The node type ID (for description lookup).", "String")),
            new EntityDef("org.knime.core.test.entity",
                "TestEnt",
                "A test entity.",
                new EntityField("xy", "The xy property.", "org.knime.core.gateway.v0.workflow.entity.XYEnt"),
                new EntityField("xylist", "The xy list property.", "List<org.knime.core.gateway.v0.workflow.entity.XYEnt>"),
                new EntityField("other", "The other property, no not this one. The other.", "String"),
                new EntityField("primitivelist", "Some simple list of strings.", "List<String>"),
                new EntityField("xymap", "Some map of properties.", "Map<String, org.knime.core.gateway.v0.workflow.entity.XYEnt>"),
                new EntityField("primitivemap", "Some simple list.", "Map<Integer, String>")));
    }
}