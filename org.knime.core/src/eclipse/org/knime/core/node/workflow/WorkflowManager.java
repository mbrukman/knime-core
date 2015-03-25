/*
 * ------------------------------------------------------------------------
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
 *   14.03.2007 (mb/bw): created
 */
package org.knime.core.node.workflow;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.lang3.StringUtils;
import org.knime.core.data.container.ContainerTable;
import org.knime.core.data.filestore.internal.FileStoreHandlerRepository;
import org.knime.core.data.filestore.internal.IFileStoreHandler;
import org.knime.core.data.filestore.internal.IWriteFileStoreHandler;
import org.knime.core.data.filestore.internal.WorkflowFileStoreHandlerRepository;
import org.knime.core.internal.ReferencedFile;
import org.knime.core.node.AbstractNodeView;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.KNIMEConstants;
import org.knime.core.node.Node;
import org.knime.core.node.NodeCreationContext;
import org.knime.core.node.NodeDialogPane;
import org.knime.core.node.NodeFactory;
import org.knime.core.node.NodeFactory.NodeType;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.NodeModel;
import org.knime.core.node.NodeSettings;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.node.NodeView;
import org.knime.core.node.NotConfigurableException;
import org.knime.core.node.dialog.DialogNode;
import org.knime.core.node.dialog.DialogNodeValue;
import org.knime.core.node.dialog.JSONOutputNode;
import org.knime.core.node.dialog.MetaNodeDialogNode;
import org.knime.core.node.exec.ThreadNodeExecutionJobManager;
import org.knime.core.node.interactive.InteractiveNode;
import org.knime.core.node.interactive.InteractiveView;
import org.knime.core.node.interactive.ReexecutionCallback;
import org.knime.core.node.interactive.ViewContent;
import org.knime.core.node.port.MetaPortInfo;
import org.knime.core.node.port.PortObject;
import org.knime.core.node.port.PortObjectSpec;
import org.knime.core.node.port.PortType;
import org.knime.core.node.port.flowvariable.FlowVariablePortObject;
import org.knime.core.node.property.hilite.HiLiteHandler;
import org.knime.core.node.util.CheckUtils;
import org.knime.core.node.util.ConvenienceMethods;
import org.knime.core.node.util.NodeExecutionJobManagerPool;
import org.knime.core.node.workflow.ConnectionContainer.ConnectionType;
import org.knime.core.node.workflow.FileWorkflowPersistor.LoadVersion;
import org.knime.core.node.workflow.FlowLoopContext.RestoredFlowLoopContext;
import org.knime.core.node.workflow.MetaNodeTemplateInformation.Role;
import org.knime.core.node.workflow.MetaNodeTemplateInformation.TemplateType;
import org.knime.core.node.workflow.MetaNodeTemplateInformation.UpdateStatus;
import org.knime.core.node.workflow.NativeNodeContainer.LoopStatus;
import org.knime.core.node.workflow.NodeContainer.NodeContainerSettings.SplitType;
import org.knime.core.node.workflow.NodeMessage.Type;
import org.knime.core.node.workflow.NodePropertyChangedEvent.NodeProperty;
import org.knime.core.node.workflow.SingleNodeContainer.SingleNodeContainerSettings;
import org.knime.core.node.workflow.Workflow.NodeAndInports;
import org.knime.core.node.workflow.WorkflowPersistor.ConnectionContainerTemplate;
import org.knime.core.node.workflow.WorkflowPersistor.LoadResult;
import org.knime.core.node.workflow.WorkflowPersistor.LoadResultEntry.LoadResultEntryType;
import org.knime.core.node.workflow.WorkflowPersistor.MetaNodeLinkUpdateResult;
import org.knime.core.node.workflow.WorkflowPersistor.NodeContainerTemplateLinkUpdateResult;
import org.knime.core.node.workflow.WorkflowPersistor.WorkflowLoadResult;
import org.knime.core.node.workflow.WorkflowPersistor.WorkflowPortTemplate;
import org.knime.core.node.workflow.execresult.NodeContainerExecutionResult;
import org.knime.core.node.workflow.execresult.NodeContainerExecutionStatus;
import org.knime.core.node.workflow.execresult.WorkflowExecutionResult;
import org.knime.core.node.workflow.virtual.parchunk.ParallelizedChunkContent;
import org.knime.core.node.workflow.virtual.parchunk.ParallelizedChunkContentMaster;
import org.knime.core.node.workflow.virtual.parchunk.VirtualParallelizedChunkNodeInput;
import org.knime.core.node.workflow.virtual.parchunk.VirtualParallelizedChunkPortObjectInNodeFactory;
import org.knime.core.node.workflow.virtual.parchunk.VirtualParallelizedChunkPortObjectInNodeModel;
import org.knime.core.node.workflow.virtual.parchunk.VirtualParallelizedChunkPortObjectOutNodeFactory;
import org.knime.core.quickform.AbstractQuickFormConfiguration;
import org.knime.core.quickform.AbstractQuickFormValueInConfiguration;
import org.knime.core.quickform.in.QuickFormInputNode;
import org.knime.core.util.FileUtil;
import org.knime.core.util.LockFailedException;
import org.knime.core.util.Pair;
import org.knime.core.util.VMFileLocker;
import org.knime.core.util.pathresolve.ResolverUtil;

/**
 * Container holding nodes and connections of a (sub) workflow. In contrast
 * to previous implementations, this class will now handle all control, such
 * as transport of data and specs from node to subsequent nodes. That is, nodes
 * do not know their pre- or successors anymore.
 * A WorkflowManager can also play the role of a NodeContainer, thus
 * representing a metanode/subworkflow.
 *
 * @author M. Berthold/B. Wiswedel, University of Konstanz
 */
public final class WorkflowManager extends NodeContainer implements NodeUIInformationListener, NodeContainerParent, NodeContainerTemplate {

    /** my logger. */
    private static final NodeLogger LOGGER = NodeLogger.getLogger(WorkflowManager.class);

    /** Name of this workflow (usually displayed at top of the node figure).
     * May be null to use name of workflow directory. */
    private String m_name;

    /** Executor for asynchronous event notification. */
    private static final Executor WORKFLOW_NOTIFIER =
        Executors.newSingleThreadExecutor(new ThreadFactory() {
            /** {@inheritDoc} */
            @Override
            public Thread newThread(final Runnable r) {
                Thread t = new Thread(r, "KNIME-Workflow-Notifier");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
        });

    /** Executor for asynchronous invocation of checkForNodeStateChanges
     * in an unconnected parent.
     * If a checkForNodeStateChanges-Thread is already waiting, additional
     * ones will be discarded. */
    private static final Executor PARENT_NOTIFIER =
        new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(2), new ThreadFactory() {
                    /** {@inheritDoc} */
                    @Override
                    public Thread newThread(final Runnable r) {
                        Thread t = new Thread(r, "KNIME-WFM-Parent-Notifier");
                        return t;
                    }
                }, new ThreadPoolExecutor.DiscardPolicy());

    // Nodes and edges forming this workflow:
    private final Workflow m_workflow;

    // Ports of the workflow (empty if it is not a subworkflow):

    /** ports of this Metanode (both arrays can have 0 length!). */
    private WorkflowInPort[] m_inPorts;
    private UIInformation m_inPortsBarUIInfo;
    private WorkflowOutPort[] m_outPorts;
    private UIInformation m_outPortsBarUIInfo;

    /** editor specific settings are stored with the workflow.
     * @since 2.6 */
    private EditorUIInformation m_editorInfo = null;


    /** Vector holding workflow specific variables. */
    private Vector<FlowVariable> m_workflowVariables;

    private final Vector<WorkflowAnnotation> m_annotations =
        new Vector<WorkflowAnnotation>();

    // Misc members:

    /** for internal usage, holding output table references. */
    private final HashMap<Integer, ContainerTable> m_globalTableRepository;

    /** The repository of all active {@link IFileStoreHandler}. It inherits
     * from the parent if this wfm is a meta node. */
    private final WorkflowFileStoreHandlerRepository m_fileStoreHandlerRepository;

    /** Password store. This object is associated with each meta-node
     * (contained meta nodes have their own password store). */
    private final CredentialsStore m_credentialsStore;

    /** The version as read from workflow.knime file during load
     * (or null if not loaded but newly created). This field is used to
     * determine whether the workflow needs to be converted to any newer version
     * upon save. */
    private LoadVersion m_loadVersion;

    /** When and by whom was workflow changed, null if not saved yet. */
    private AuthorInformation m_authorInformation;

    /** Template information encapsulating template source URI and reference
     * date. This field is {@link MetaNodeTemplateInformation#NONE} for workflow
     * projects and meta nodes, which are not used as linked templates. */
    private MetaNodeTemplateInformation m_templateInformation;

    /** True if the underlying folder is RO (WFM will be write protected). This
     * flag is set during load. */
    private boolean m_isWorkflowDirectoryReadonly;

    /** Listeners interested in status changes. */
    private final CopyOnWriteArrayList<WorkflowListener> m_wfmListeners;

    /**
     * Semaphore to make sure we never deal with inconsistent nodes within the
     * workflow. Changes to state or outputs (port/data) need to synchronize
     * against this so that nodes collecting input (states/specs/data) can make
     * sure that they look at one consistent "snapshot" of a workflow. This
     * semaphore will be used by all "connected" children of this node. Isolated
     * workflows create a new semaphore.
     */
    private final Object m_workflowMutex;

    /** see {@link #getDirectNCParent()}. */
    private final NodeContainerParent m_directNCParent;

    /** A lock handle identifying this workflow/meta node as encrypted. See
     * {@link WorkflowCipher} for details on what is locked/encrypted.
     * @since 2.5 */
    private WorkflowCipher m_cipher = WorkflowCipher.NULL_CIPHER;

    private final WorkflowContext m_workflowContext;

    /** The root of everything, a workflow with no in- or outputs.
     * This workflow holds the top level projects. */
    public static final WorkflowManager ROOT = new WorkflowManager(
        null, null, NodeID.ROOTID, new PortType[0], new PortType[0], true, null, "ROOT", null, null);

    /** dir where all tmp files of the flow live. Set in the workflow context. If not null, it must be discarded upon
     * workflow disposal. If null, the temp dir location in the context was set from someone else (the server e.g.) and
     * it must not be deleted by the workflow manager. */
    private File m_tmpDir = null;

    ///////////////////////
    // Constructors
    ///////////////////////

    /**
     * Constructor - create new child workflow container with a parent,
     * a new ID, and the number and type of in/outports as specified.
     *
     * @param directNCParent the direct parent, i.e. a {@link SubNodeContainer} or null
     * (then <code>parent</code> is used)
     * @param parent Parent of this workflow manager
     * @param id ID of this workflow manager
     * @param inTypes Types of the input ports
     * @param outTypes Types of the output ports
     * @param isProject If this workflow manager is a project
     * @param context The context
     * @param name Name of this workflow manager
     * @param globalTableRepository TODO
     * @param fileStoreHandlerRepository TODO
     */
    WorkflowManager(final NodeContainerParent directNCParent, final WorkflowManager parent, final NodeID id,
        final PortType[] inTypes, final PortType[] outTypes, final boolean isProject, final WorkflowContext context,
        final String name, final HashMap<Integer, ContainerTable> globalTableRepository,
        final WorkflowFileStoreHandlerRepository fileStoreHandlerRepository) {
        super(parent, id);
        m_directNCParent = assertParentAssignments(directNCParent, parent);
        m_workflow = new Workflow(this, id);
        m_inPorts = new WorkflowInPort[inTypes.length];
        for (int i = 0; i < inTypes.length; i++) {
            m_inPorts[i] = new WorkflowInPort(i, inTypes[i]);
        }
        m_outPorts = new WorkflowOutPort[outTypes.length];
        for (int i = 0; i < outTypes.length; i++) {
            m_outPorts[i] = new WorkflowOutPort(i, outTypes[i]);
        }
        m_name = name;
        boolean noPorts = m_inPorts.length == 0 && m_outPorts.length == 0;
        assert !isProject || noPorts; // projects must not have ports
        if (isProject) {
            // we can start a new table repository since there can not
            // be any dependencies to parent
            m_globalTableRepository = new GlobalTableRepository();
            m_fileStoreHandlerRepository = new WorkflowFileStoreHandlerRepository();
            // ...and we do not need to synchronize across unconnected workflows
            m_workflowMutex = new Object();
            m_workflowContext = context;
            if (context != null) {
                createAndSetWorkflowTempDirectory(context);
            }
        } else {
            // ...synchronize across border
            m_workflowMutex = m_directNCParent.getWorkflowMutex();
            // otherwise we may have incoming and/or outgoing dependencies...
            m_globalTableRepository = globalTableRepository;
            m_fileStoreHandlerRepository = fileStoreHandlerRepository;
            m_workflowContext = context;
        }
        m_credentialsStore = new CredentialsStore(this);
        // initialize listener list
        m_wfmListeners = new CopyOnWriteArrayList<WorkflowListener>();
        m_templateInformation = MetaNodeTemplateInformation.NONE;
        synchronized (m_workflowMutex) { // asserted in check -- even from constructor
            checkForNodeStateChanges(false); // get default state right
        }
        LOGGER.debug("Created subworkflow " + this.getID());
    }

    private NodeContainerParent assertParentAssignments(final NodeContainerParent directNCParent,
        final WorkflowManager parent) {
        if (directNCParent == null && parent == null) {
            // this is ROOT
            return null;
        } else if (directNCParent != null && parent == null) {
            // instance is used in a sub node container
            return directNCParent;
        } else if (directNCParent == null && parent != null) {
            // standard case: either a project or a meta node
            return parent;
        } else {
            throw new IllegalArgumentException("Parent assignments misleading; can only have one parent (type)");
        }
    }

    /**
     * Constructor - create new workflow from persistor.
     * @param directNCParent TODO
     * @param parent The parent of this workflow
     * @param id The ID of this workflow
     * @param persistor Persistor containing the content for this workflow
     * @param globalTableRepository ...
     * @param fileStoreHandlerRepository ...
     */
    WorkflowManager(final NodeContainerParent directNCParent, final WorkflowManager parent, final NodeID id,
        final WorkflowPersistor persistor, final HashMap<Integer, ContainerTable> globalTableRepository,
        final WorkflowFileStoreHandlerRepository fileStoreHandlerRepository) {
        super(parent, id, persistor.getMetaPersistor());
        m_directNCParent = assertParentAssignments(directNCParent, parent);
        ReferencedFile ncDir = super.getNodeContainerDirectory();
        final boolean isProject = persistor.isProject();
        if (ncDir != null && isProject) { // only lock projects
            if (!ncDir.fileLockRootForVM()) {
                throw new IllegalStateException("Root directory to workflow \""
                        + ncDir + "\" can't be locked although it should have "
                        + "been locked by the load routines");
            }
        }
        m_workflow = new Workflow(this, id);
        m_name = persistor.getName();
        m_editorInfo = persistor.getEditorUIInformation();
        m_templateInformation = persistor.getTemplateInformation();
        m_authorInformation = persistor.getAuthorInformation();
        m_loadVersion = persistor.getLoadVersion();
        m_workflowVariables = new Vector<FlowVariable>(persistor.getWorkflowVariables());
        m_credentialsStore = new CredentialsStore(this, persistor.getCredentials());
        m_cipher = persistor.getWorkflowCipher();
        WorkflowContext workflowContext;
        if (isProject) {
            workflowContext = persistor.getWorkflowContext();
            if (workflowContext == null && getNodeContainerDirectory() != null) { // real projects have a file loc
                LOGGER.warn("No workflow context available for " + m_name , new Throwable());
                workflowContext = new WorkflowContext.Factory(getNodeContainerDirectory().getFile()).createContext();
            }
            if (workflowContext != null) {
                createAndSetWorkflowTempDirectory(workflowContext);
            }
        } else {
            workflowContext = null;
        }
        m_workflowContext = workflowContext;
        WorkflowPortTemplate[] inPortTemplates = persistor.getInPortTemplates();
        m_inPorts = new WorkflowInPort[inPortTemplates.length];
        for (int i = 0; i < inPortTemplates.length; i++) {
            WorkflowPortTemplate t = inPortTemplates[i];
            m_inPorts[i] = new WorkflowInPort(t.getPortIndex(), t.getPortType());
            m_inPorts[i].setPortName(t.getPortName());
        }
        m_inPortsBarUIInfo = persistor.getInPortsBarUIInfo();

        WorkflowPortTemplate[] outPortTemplates = persistor.getOutPortTemplates();
        m_outPorts = new WorkflowOutPort[outPortTemplates.length];
        for (int i = 0; i < outPortTemplates.length; i++) {
            WorkflowPortTemplate t = outPortTemplates[i];
            m_outPorts[i] = new WorkflowOutPort(t.getPortIndex(), t.getPortType());
            m_outPorts[i].setPortName(t.getPortName());
        }
        m_outPortsBarUIInfo = persistor.getOutPortsBarUIInfo();

        boolean noPorts = m_inPorts.length == 0 && m_outPorts.length == 0;
        assert !isProject || noPorts; // projects must not have ports

        if (isProject) {
            m_workflowMutex = new Object();
            m_globalTableRepository = persistor.getGlobalTableRepository();
            m_fileStoreHandlerRepository = persistor.getFileStoreHandlerRepository();
        } else {
            m_workflowMutex = m_directNCParent.getWorkflowMutex();
            m_globalTableRepository = globalTableRepository;
            m_fileStoreHandlerRepository = fileStoreHandlerRepository;
        }
        m_wfmListeners = new CopyOnWriteArrayList<WorkflowListener>();
        LOGGER.debug("Created subworkflow " + this.getID());
    }

    /**
     * @return workflow
     */
    Workflow getWorkflow() {
        return m_workflow;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getWorkflowMutex() {
        return m_workflowMutex;
    }

    /** {@inheritDoc} */
    @Override
    public NodeContainerParent getDirectNCParent() {
        return m_directNCParent;
    }

    /**
     * {@inheritDoc}
     * @since 2.10
     */
    @Override
    public WorkflowManager getProjectWFM() {
        if (isProject()) {
            return this;
        } else {
            return getDirectNCParent().getProjectWFM();
        }
    }

//    public WorkflowManager getProjectFor(final NodeContainer nc) {
//        NodeContainerParent ncParent;
//        do {
//            if (nc instanceof WorkflowManager) {
//                WorkflowManager m = (WorkflowManager)nc;
//                if (m.isProject()) {
//                    return m;
//                }
//                ncParent = m.getDirectNCParent();
//            } else {
//                ncParent = nc.getParent();
//            }
//        } while (ncParent != null);
//        throw new IllegalStateException("NodeContainer \"" + nc + "\" doesn't appear to have a project parent");
//    }

//    public WorkflowManager getWFMParentFor(final NodeContainer nc) {
//        NodeContainerParent ncParent;
//        do {
//            if (nc instanceof WorkflowManager) {
//                WorkflowManager m = (WorkflowManager)nc;
//                if (m.isProject()) {
//                    return m;
//                }
//                ncParent = m.getDirectNCParent();
//            } else {
//                ncParent = nc.getParent();
//            }
//        } while (ncParent != null);
//        throw new IllegalStateException("NodeContainer \"" + nc + "\" doesn't appear to have a project parent");
//    }

    ///////////////////////////////////////
    // Node / Project / Metanode operations
    ///////////////////////////////////////

    /** Create new project - which is the same as creating a new subworkflow
     * at this level with no in- or outports.
     * @param name the name of the workflow (<code>null</code> value is ok)
     * @param creationHelper a workflow creation helper instance, must not be <code>null</code>
     * @return newly created workflow
     * @since 2.8
     */
    public WorkflowManager createAndAddProject(final String name, final WorkflowCreationHelper creationHelper) {
        WorkflowManager wfm = createAndAddSubWorkflow(
            new PortType[0], new PortType[0], name, true, creationHelper.getWorkflowContext());
        LOGGER.debug("Created project " + ((NodeContainer)wfm).getID());
        return wfm;
    }

    /** Remove a project - the same as remove node but we make sure it really
     * looks like a project (i.e. has no in- or outports).
     *
     * @param id of the project to be removed.
     */
    public void removeProject(final NodeID id) {
        synchronized (m_workflowMutex) {
            NodeContainer nc = getNodeContainer(id);
            if (nc instanceof WorkflowManager && ((WorkflowManager)nc).isProject()) {
                final String nameAndID = "\"" + nc.getNameWithID() + "\"";
                LOGGER.debug("Removing project " + nameAndID);
                ((WorkflowManager)nc).shutdown();
                removeNode(id);
                LOGGER.debug("Project " + nameAndID + " removed (" + m_workflow.getNrNodes() + " remaining)");
            } else {
                throw new IllegalArgumentException("Node: " + id + " is not a project!");
            }
        }
    }

    /** Uses given Factory to create a new node and then adds new node to the
     * workflow manager. We will automatically find the next available free
     * index for the new node within the given prefix.
     *
     * @param factory NodeFactory used to create the new node
     * @return newly created (unique) NodeID
     */
    // FIXME: I don't like this type cast warning (and the ? for that matter!)
    public NodeID createAndAddNode(final NodeFactory<?> factory) {
        return internalAddNewNode(factory, null);
    }

    /** Create new Node based on given factory and add to workflow.
     *
     * @param factory ...
     * @return unique ID of the newly created and inserted node.
     * @since 2.9
     */
    public NodeID addNode(final NodeFactory<?> factory) {
        return addNodeAndApplyContext(factory, null);
    }

    /**
     * @param factory ...
     * @param context the context provided by the framework (e.g. the URL of the file that was dragged on the canvas)
     * @return the node id of the created node.
     */
    public NodeID addNodeAndApplyContext(final NodeFactory<?> factory,
            final NodeCreationContext context) {
        return internalAddNewNode(factory, context);
    }

    @SuppressWarnings("unchecked")
    private NodeID internalAddNewNode(final NodeFactory<?> factory,
            final NodeCreationContext context) {
        synchronized (m_workflowMutex) {
            // TODO synchronize to avoid messing with running workflows!
            assert factory != null;
            // insert node
            NodeID newID = m_workflow.createUniqueID();
            NativeNodeContainer container = new NativeNodeContainer(this,
                new Node((NodeFactory<NodeModel>)factory, context), newID);
            addNodeContainer(container, true);
            configureNodeAndSuccessors(newID, true);
            if (context != null) { // save node settings if source URL/context was provided (bug 5772)
                container.saveNodeSettingsToDefault();
            }
            LOGGER.debug("Added new node " + newID);
            setDirty();
            return newID;
        }
    }

    /** Check if specific node can be removed (i.e. is not currently being
     * executed or waiting to be).
     *
     * @param nodeID id of node to be removed
     * @return true if node can safely be removed.
     */
    public boolean canRemoveNode(final NodeID nodeID) {
        synchronized (m_workflowMutex) {
            // check to make sure we can safely remove this node
            NodeContainer nc = m_workflow.getNode(nodeID);
            if (nc == null) {
                return false;
            }
            if (nc.getInternalState().isExecutionInProgress()) {
                return false;
            }
            if (!nc.isDeletable()) {
                return false;
            }
            for (ConnectionContainer c : m_workflow.getConnectionsByDest(nodeID)) {
                if (!c.isDeletable()) {
                    return false;
                }
            }
            for (ConnectionContainer c : m_workflow.getConnectionsBySource(nodeID)) {
                if (!c.isDeletable()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Remove node if possible. Throws an exception if node is "busy" and can
     * not be removed at this time. If the node does not exist, this method
     * returns without exception.
     *
     * @param nodeID id of node to be removed
     */
    public void removeNode(final NodeID nodeID) {
        NodeContainer nc;
        synchronized (m_workflowMutex) {
            // if node does not exist, simply return
            if (m_workflow.getNode(nodeID) == null) {
                return;
            }
            // check to make sure we can safely remove this node
            if (!canRemoveNode(nodeID)) {
                throw new IllegalStateException("Node cannot be removed");
            }
            // remove lists of in- and outgoing connections.
            while (!m_workflow.getConnectionsByDest(nodeID).isEmpty()) {
                ConnectionContainer toDel =  m_workflow.getConnectionsByDest(nodeID).iterator().next();
                removeConnection(toDel);
            }
            while (!m_workflow.getConnectionsBySource(nodeID).isEmpty()) {
                ConnectionContainer toDel = m_workflow.getConnectionsBySource(nodeID).iterator().next();
                removeConnection(toDel);
            }
            // and finally remove node itself as well.
            nc = m_workflow.removeNode(nodeID);
            nc.cleanup();
            // update list of obsolete node directories for non-root wfm
            ReferencedFile ncDir = nc.getNodeContainerDirectory();
            if (this != ROOT && ncDir != null && getNodeContainerDirectory() != null) {
                getNodeContainerDirectory().getDeletedNodesFileLocations().add(ncDir);
            }
            ReferencedFile autoSaveDir = nc.getAutoSaveDirectory();
            if (this != ROOT && autoSaveDir != null && getAutoSaveDirectory() != null) {
                getAutoSaveDirectory().getDeletedNodesFileLocations().add(autoSaveDir);
            }
            checkForNodeStateChanges(true);
        }
        setDirty();
        notifyWorkflowListeners(new WorkflowEvent(WorkflowEvent.Type.NODE_REMOVED, getID(), nc, null));
    }

    /** Creates new meta node. We will automatically find the next available
     * free index for the new node within this workflow.
     * @param inPorts types of external inputs (going into this workflow)
     * @param outPorts types of external outputs (exiting this workflow)
     * @param name Name of the workflow (null values will be handled)
     * @return newly created <code>WorkflowManager</code>
     */
    public WorkflowManager createAndAddSubWorkflow(final PortType[] inPorts,
            final PortType[] outPorts, final String name) {
        return createAndAddSubWorkflow(inPorts, outPorts, name, false, null);
    }

    /** Adds new empty meta node to this WFM. */
    private WorkflowManager createAndAddSubWorkflow(final PortType[] inPorts,
            final PortType[] outPorts, final String name, final boolean isNewProject, final WorkflowContext context) {
        final boolean hasPorts = inPorts.length != 0 || outPorts.length != 0;
        if (this == ROOT) {
            if (hasPorts) {
                throw new IllegalStateException("Can't create sub workflow on root workflow manager, "
                        + "use createAndAddProject() instead");
            }
            if (!isNewProject) {
                throw new IllegalStateException("Children of ROOT workflow manager must have 'isProject' flag set");
            }
        }
        if (isNewProject && hasPorts) {
            throw new IllegalStateException("Projects must not have ports");
        }
        NodeID newID;
        WorkflowManager wfm;
        synchronized (m_workflowMutex) {
            newID = m_workflow.createUniqueID();
            HashMap<Integer, ContainerTable> globalTableRepository = isNewProject ? null : m_globalTableRepository;
            WorkflowFileStoreHandlerRepository fileStoreRepository = isNewProject ? null : m_fileStoreHandlerRepository;
            wfm = new WorkflowManager(null, this, newID, inPorts, outPorts, isNewProject, context, name,
                globalTableRepository, fileStoreRepository);
            addNodeContainer(wfm, true);
            LOGGER.debug("Added new subworkflow " + newID);
        }
        setDirty();
        return wfm;
    }

    /** Returns true if this workflow manager is a project (which usually means
     * that the parent is {@link #ROOT}). It returns false if this workflow
     * is only a meta node in another meta node or project.
     * @return This property.
     * @since 2.6 */
    public boolean isProject() {
        return this == ROOT || m_workflowMutex != getDirectNCParent().getWorkflowMutex();
    }

    /** Creates new meta node from a persistor instance.
     * @param persistor to read from
     * @param newID new id to be used
     * @return newly created <code>WorkflowManager</code>
     */
    WorkflowManager createSubWorkflow(final WorkflowPersistor persistor,
            final NodeID newID) {
        if (!newID.hasSamePrefix(getID()) || m_workflow.containsNodeKey(newID)) {
            throw new RuntimeException(
                    "Invalid or duplicate ID \"" + newID + "\"");
        }
        WorkflowManager wfm = new WorkflowManager(null, this, newID, persistor,
            m_globalTableRepository, m_fileStoreHandlerRepository);
        return wfm;
    }

    ////////////////////////////////////////////
    // Helper methods for Node/Workflow creation
    ////////////////////////////////////////////

    /** Adds the NodeContainer to m_nodes and adds empty connection sets to
     * m_connectionsBySource and m_connectionsByDest.
     * @param nodeContainer new Container to add.
     * @param propagateChanges Whether to also check workflow state
     * (this is always true unless called from the load routines)
     */
    private void addNodeContainer(final NodeContainer nodeContainer, final boolean propagateChanges) {
        assert Thread.holdsLock(m_workflowMutex);
        if (this == ROOT && !(nodeContainer instanceof WorkflowManager)) {
            throw new IllegalStateException("Can't add ordinary node to root "
                    + "workflow, use createAndAddProject() first");
        }
        if (this == ROOT && (nodeContainer.getNrInPorts() != 0 || nodeContainer.getNrOutPorts() != 0)) {
            throw new IllegalStateException("Can't add sub workflow to root "
                    + " workflow, use createProject() instead");
        }
        NodeID id = nodeContainer.getID();
        synchronized (m_workflowMutex) {
            assert !m_workflow.containsNodeKey(id) : "\""
               + nodeContainer.getNameWithID() + "\" already contained in flow";
            m_workflow.putNode(id, nodeContainer);
        }
        notifyWorkflowListeners(new WorkflowEvent(WorkflowEvent.Type.NODE_ADDED,
                id, null, nodeContainer));
        checkForNodeStateChanges(propagateChanges);
    }

    ///////////////////////////
    // Connection operations
    ///////////////////////////

    /** Add new connection - throw Exception if the same connection
     * already exists.
     *
     * @param source node id
     * @param sourcePort port index at source node
     * @param dest destination node id
     * @param destPort port index at destination node
     * @return newly created Connection object
     * @throws IllegalArgumentException if connection already exists
     */
    public ConnectionContainer addConnection(final NodeID source,
            final int sourcePort, final NodeID dest,
            final int destPort) {
        // must not set it in the private method (see below), as the private
        // one is also called from the load routine
        setDirty();
        return addConnection(source, sourcePort, dest, destPort, false);
    }

    /** Add new connection - throw Exception if the same connection
     * already exists.
     *
     * @param source node id
     * @param sourcePort port index at source node
     * @param dest destination node id
     * @param destPort port index at destination node
     * @param currentlyLoadingFlow True if the flow is currently loading
     *        its content, it will then skip the configuration of the
     *        destination node and allow node insertion in case the dest
     *        node is currently (remotely!) executing.
     * @return newly created Connection object
     * @throws IllegalArgumentException if connection already exists
     */
    private ConnectionContainer addConnection(final NodeID source,
            final int sourcePort, final NodeID dest,
            final int destPort, final boolean currentlyLoadingFlow) {
        assert source != null;
        assert dest != null;
        assert sourcePort >= 0;
        assert destPort >= 0;
        ConnectionContainer newConn = null;
        ConnectionType newConnType = null;
        NodeContainer sourceNC;
        NodeContainer destNC;
        synchronized (m_workflowMutex) {
            if (!canAddConnection(source, sourcePort, dest, destPort, true, currentlyLoadingFlow)) {
                throw new IllegalArgumentException("Cannot add connection!");
            }
            // check for existence of a connection to the destNode/Port
            Set<ConnectionContainer> scc = m_workflow.getConnectionsByDest(dest);
            ConnectionContainer removeCCfirst = null;
            for (ConnectionContainer cc : scc) {
                if (cc.getDestPort() == destPort) {
                    removeCCfirst = cc;
                }
            }
            if (removeCCfirst != null) {
                removeConnection(removeCCfirst);
            }
            // cleaned up - now add new connection
            sourceNC = m_workflow.getNode(source);
            destNC = m_workflow.getNode(dest);
            // determine type of new connection:
            if ((sourceNC == null) && (destNC == null)) {
                newConnType = ConnectionType.WFMTHROUGH;
            } else if (sourceNC == null) {
                newConnType = ConnectionType.WFMIN;
            } else if (destNC == null) {
                newConnType = ConnectionType.WFMOUT;
            } else {
                newConnType = ConnectionType.STD;
            }
            // create new connection
            newConn = new ConnectionContainer(source, sourcePort, dest, destPort, newConnType);
            m_workflow.addConnection(newConn);
            // handle special cases with port reference chains (WFM border
            // crossing connections:
            if ((source.equals(getID())) && (dest.equals(getID()))) {
                // connection goes directly from workflow in to workflow outport
                assert newConnType == ConnectionType.WFMTHROUGH;
                getOutPort(destPort).setUnderlyingPort(getWorkflowIncomingPort(sourcePort));
            } else if ((!dest.equals(getID())) && (destNC instanceof WorkflowManager)) {
                // we are feeding data into a subworkflow
                WorkflowInPort wfmIPort = ((WorkflowManager)destNC).getInPort(destPort);
                NodeOutPort underlyingPort;
                if (sourceNC != null) {
                    underlyingPort = sourceNC.getOutPort(sourcePort);
                } else {
                    assert source.equals(getID());
                    underlyingPort = getWorkflowIncomingPort(sourcePort);
                }
                wfmIPort.setUnderlyingPort(underlyingPort);
            } else if (dest.equals(getID())) {
                // we are feeding data out of the subworkflow
                assert newConnType == ConnectionType.WFMOUT;
                if (sourceNC != null) {
                    getOutPort(destPort).setUnderlyingPort(sourceNC.getOutPort(sourcePort));
                }
            }
            if (!currentlyLoadingFlow) { // user adds connection -> configure
                if (newConn.getType().isLeavingWorkflow()) {
                    assert !m_workflow.containsNodeKey(dest);
                    // if the destination was the WFM itself, only configure its
                    // successors one layer up!
                    getParent().configureNodeAndSuccessors(dest, false);
                    checkForNodeStateChanges(true);
                } else if (destNC instanceof WorkflowManager) {
                    // connection enters a meta node
                    // (can't have optional ins -- no reset required)
                    WorkflowManager destWFM = (WorkflowManager)destNC;
                    destWFM.configureNodesConnectedToPortInWFM(Collections.singleton(destPort));
                    Set<Integer> outPorts = destWFM.getWorkflow().connectedOutPorts(destPort);
                    configureNodeAndPortSuccessors(dest, outPorts,
                        /* do not configure dest itself */false, true);
                } else {
                    assert m_workflow.containsNodeKey(dest);
                    // ...make sure the destination node is configured again (and
                    // all of its successors if needed)
                    // (reset required if optional input is connected)
                    resetAndConfigureNode(dest);
                }
            }
        }
        // and finally notify listeners
        notifyWorkflowListeners(new WorkflowEvent(
                WorkflowEvent.Type.CONNECTION_ADDED, null, null, newConn));
        LOGGER.debug("Added new connection from node " + source
                + "(" + sourcePort + ")" + " to node " + dest
                + "(" + destPort + ")");
        return newConn;
    }

    /** Check if a new connection can be added.
     *
     * @param source node id
     * @param sourcePort port index at source node
     * @param dest destination node id
     * @param destPort port index at destination node
     * @return true if connection can be added.
     */
    public boolean canAddConnection(final NodeID source,
            final int sourcePort, final NodeID dest,
            final int destPort) {
        return canAddConnection(source, sourcePort, dest, destPort, true, false);
    }

    /**
     * @param source ID of the source node
     * @param sourcePort Index of the sources port
     * @param dest ID of the destination node
     * @param destPort Index of the destination port
     * @return true if the connection can be added, false otherwise
     * @since 2.6
     */
    public boolean canAddNewConnection(final NodeID source,
            final int sourcePort, final NodeID dest,
            final int destPort) {
        return canAddConnection(source, sourcePort, dest, destPort, false, false);
    }

    /** see {@link #canAddConnection(NodeID, int, NodeID, int)}. If the flag
     * is set it will skip the check whether the destination node is
     * executing (the node may be executing remotely during load)
     */
    private boolean canAddConnection(final NodeID source,
            final int sourcePort, final NodeID dest,
            final int destPort, final boolean allowConnRemoval, final boolean currentlyLoadingFlow) {
        synchronized (m_workflowMutex) {
            if (source == null || dest == null) {
                return false;
            }
            // get NodeContainer for source/dest - can be null for WFM-connections!
            NodeContainer sourceNode = m_workflow.getNode(source);
            NodeContainer destNode = m_workflow.getNode(dest);
            // sanity checks (index/null)
            if (!(source.equals(this.getID()) || (sourceNode != null))) {
                return false;  // source node exists or is WFM itself
            }
            if (!(dest.equals(this.getID()) || (destNode != null))) {
                return false;  // dest node exists or is WFM itself
            }
            if ((sourcePort < 0) || (destPort < 0)) {
                return false;  // port indices are >= 0
            }
            if (sourceNode != null) {
                if (sourceNode.getNrOutPorts() <= sourcePort) {
                    return false;  // source Node index exists
                }
            } else {
                if (this.getNrInPorts() <= sourcePort) {
                    return false;  // WFM inport index exists
                }
            }
            if (destNode != null) { // ordinary node
                if (destNode.getNrInPorts() <= destPort) {
                    return false;  // dest Node index exists
                }
                // omit execution checks during loading (dest node may
                // be executing remotely -- SGE execution)
                if (!currentlyLoadingFlow) {
                    // destination node may have optional inputs
                    if (hasSuccessorInProgress(dest)) {
                        return false;
                    }
                    if (destNode.getInternalState().isExecutionInProgress()) {
                        return false;
                    }
                }
            } else { // leaving workflow connection
                assert dest.equals(getID());
                if (this.getNrOutPorts() <= destPort) {
                    return false;  // WFM outport index exists
                }
                // node may be executing during load (remote cluster execution)
                if (!currentlyLoadingFlow) {
                    // nodes with optional inputs may have executing successors
                    // note it is ok if the WFM itself is executing...
                    if (getParent().hasSuccessorInProgress(getID())) {
                        return false;
                    }
                }
            }
            // check if we are about to replace an existing connection
            for (ConnectionContainer cc : m_workflow.getConnectionsByDest(dest)) {
                if (cc.getDestPort() == destPort) {
                    // if that connection is not removable: fail
                    if (!allowConnRemoval || !canRemoveConnection(cc)) {
                        return false;
                    }
                }
            }
            // check type compatibility
            PortType sourceType = (sourceNode != null
                ? sourceNode.getOutPort(sourcePort).getPortType()
                : this.getInPort(sourcePort).getPortType());
            PortType destType = (destNode != null
                ? destNode.getInPort(destPort).getPortType()
                : this.getOutPort(destPort).getPortType());
            /* ports can be connected in two cases (one exception below):
             * - the destination type is a super type or the same type
             *   of the source port (usual case) or
             * - if the source port is a super type of the destination port,
             *   for instance a reader node that reads a general PMML objects,
             *   validity is checked using the runtime class of the actual
             *   port object then
             * if one port is a BDT and the other is not, no connection is allowed
             * */
            Class<? extends PortObject> sourceCl = sourceType.getPortObjectClass();
            Class<? extends PortObject> destCl = destType.getPortObjectClass();
            if (BufferedDataTable.class.equals(sourceCl)
                    && !BufferedDataTable.class.equals(destCl)) {
                return false;
            } else if (BufferedDataTable.class.equals(destCl)
                    && !BufferedDataTable.class.equals(sourceCl)) {
                return false;
            } else if (!destCl.isAssignableFrom(sourceCl)
                && !sourceCl.isAssignableFrom(destCl)) {
                return false;
            }
            // and finally check if we are threatening to close a loop (if we
            // are not trying to leave this metanode, of course).
            if (!dest.equals(this.getID())
                    && !source.equals(this.getID())) {
                Set<NodeID> sNodes
                    = m_workflow.getBreadthFirstListOfNodeAndSuccessors(
                            dest, true).keySet();
                if (sNodes.contains(source)) {
                    return false;
                }
            }
            // no reason to say no found - return true.
            return true;
        }
    }

    /** Check if a connection can safely be removed.
     *
     * @param cc connection
     * @return true if connection cc is removable.
     */
    public boolean canRemoveConnection(final ConnectionContainer cc) {
        synchronized (m_workflowMutex) {
            if (cc == null || !cc.isDeletable()) {
                return false;
            }
            NodeID destID = cc.getDest();
            NodeID sourceID = cc.getSource();
            // make sure both nodes (well, their connection lists) exist
            if (m_workflow.getConnectionsByDest(destID) == null) {
                return false;
            }
            if (m_workflow.getConnectionsBySource(sourceID) == null) {
                return false;
            }
            // make sure connection between those two nodes exists
            if (!m_workflow.getConnectionsByDest(destID).contains(cc)) {
                return false;
            }
            if (!m_workflow.getConnectionsBySource(sourceID).contains(cc)) {
                return false;
            }
            if (destID.equals(getID())) { // wfm out connection
                // note it is ok if the WFM itself is executing...
                if (getParent().hasSuccessorInProgress(getID())) {
                    return false;
                }
            } else {
                final InternalNodeContainerState internalState = m_workflow.getNode(destID).getInternalState();
                if (internalState.isExecutionInProgress() || (internalState.isExecuted() && !canResetNode(destID))) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Remove connection.
     *
     * @param cc connection
     */
    public void removeConnection(final ConnectionContainer cc) {
        synchronized (m_workflowMutex) {
            // make sure both nodes (well, their connection lists) exist
            if (m_workflow.getConnectionsByDest(cc.getDest()) == null) {
                return;
            }
            if (m_workflow.getConnectionsBySource(cc.getSource()) == null) {
                return;
            }
            // make sure connection exists
            if ((!m_workflow.getConnectionsByDest(cc.getDest()).contains(cc))) {
                if ((!m_workflow.getConnectionsBySource(cc.getSource()).contains(cc))) {
                    // if connection doesn't exist anywhere, we are fine
                    return;
                } else {
                    // this should never happen - only one direction exists
                    assert false;
                    throw new IllegalArgumentException("Cannot remove partially existing connection!");
                }
            }
            // now check if other reasons forbit to delete this connection:
            if (!canRemoveConnection(cc)) {
                throw new IllegalStateException("Cannot remove connection!");
            }
            // check type and underlying nodes
            NodeID source = cc.getSource();
            NodeID dest = cc.getDest();
            int destPort = cc.getDestPort();
            NodeContainer sourceNC = m_workflow.getNode(source);
            NodeContainer destNC = m_workflow.getNode(dest);
            assert (source.equals(this.getID())) || (sourceNC != null);
            assert (dest.equals(this.getID())) || (destNC != null);
            if ((sourceNC == null) && (destNC == null)) {
                assert cc.getType() == ConnectionType.WFMTHROUGH;
            } else if (sourceNC == null) {
                assert cc.getType() == ConnectionType.WFMIN;
            } else if (destNC == null) {
                assert cc.getType() == ConnectionType.WFMOUT;
            } else {
                assert cc.getType() == ConnectionType.STD;
            }
            // 0) clean everything after this connection:
            // (note that just reseting everything connected to the
            //  outport of the sourcenode does not work - there may
            //  be other connections!)
            if (destNC instanceof SingleNodeContainer) {
                // connection goes directly into normal node:
                resetNodeAndSuccessors(dest);
            } else if (!dest.equals(this.getID())) {
                // connection goes into a metanode
                WorkflowManager destWFM = (WorkflowManager)destNC;
                if (destWFM != null) {
                    destWFM.resetNodesInWFMConnectedToInPorts(Collections.singleton(cc.getDestPort()));
                    // also reset successors of this "port"
                    Set<Integer> outPorts = destWFM.getWorkflow().connectedOutPorts(destPort);
                    for (int i : outPorts) {
                        resetSuccessors(dest, i);
                    }
                }
            } else {
                // connection leaves workflow
                assert cc.getType().isLeavingWorkflow();
                getParent().resetSuccessors(this.getID(), cc.getDestPort());
            }
            // and finally delete connection from workflow
            m_workflow.removeConnection(cc);
            // cleanup for special cases with port reference chains (WFM border crossing connections:
            if ((source.equals(getID())) && (dest.equals(getID()))) {
                // connection goes directly from workflow in to workflow outport
                assert cc.getType() == ConnectionType.WFMTHROUGH;
                getOutPort(destPort).setUnderlyingPort(null);
            } else if ((!dest.equals(getID())) && (destNC instanceof WorkflowManager)) {
                // we are feeding data into a subworkflow
                WorkflowInPort wfmIPort = ((WorkflowManager)destNC).getInPort(destPort);
                wfmIPort.setUnderlyingPort(null);
            } else if (dest.equals(getID())) {
                // we are feeding data out of the subworkflow
                assert cc.getType() == ConnectionType.WFMOUT;
                getOutPort(destPort).setUnderlyingPort(null);
            }
            // and finally reconfigure destination node(s)
            if (cc.getType().isLeavingWorkflow()) {
                // this is a bit too broad (configure ALL successors) but should not be harmful
                this.getParent().configureNodeAndSuccessors(this.getID(), false);
                // make sure to reflect state changes
                checkForNodeStateChanges(true);
            } else if (destNC instanceof WorkflowManager) {
                // connection entered a meta node
                WorkflowManager destWFM = (WorkflowManager)destNC;
                destWFM.configureNodesConnectedToPortInWFM(Collections.singleton(destPort));
                // also configure successors (too broad again, see above)
                configureNodeAndSuccessors(dest, false);
            } else {
                // otherwise just configure successor
                configureNodeAndSuccessors(dest, true);
            }
        }
        setDirty();
        notifyWorkflowListeners(new WorkflowEvent(WorkflowEvent.Type.CONNECTION_REMOVED, null, cc, null));
    }

    /////////////////////////////////
    // Utility Connection Functions
    /////////////////////////////////

    /**
     * Returns the set of outgoing connections for the node with the passed id
     * at the specified port.
     *
     * @param id id of the node of interest
     * @param portIdx port index of that node
     * @return all outgoing connections for the passed node at the specified
     *  port
     */
    public Set<ConnectionContainer> getOutgoingConnectionsFor(final NodeID id,
            final int portIdx) {
        synchronized (m_workflowMutex) {
            Set<ConnectionContainer> outConnections = m_workflow.getConnectionsBySource(id);
            Set<ConnectionContainer> outConsForPort = new HashSet<ConnectionContainer>();
            if (outConnections == null) {
                return outConsForPort;
            }
            for (ConnectionContainer cont : outConnections) {
                if (cont.getSourcePort() == portIdx) {
                    outConsForPort.add(cont);
                }
            }
            return outConsForPort;
        }
    }

    /** Get all outgoing connections for a node.
     * @param id The requested node
     * @return All current outgoing connections in a new set.
     * @throws IllegalArgumentException If the node is unknown or null.
     */
    public Set<ConnectionContainer> getOutgoingConnectionsFor(final NodeID id) {
        synchronized (m_workflowMutex) {
            getNodeContainer(id); // for exception handling
            return new LinkedHashSet<ConnectionContainer>(m_workflow.getConnectionsBySource(id));
        }
    }

    /**
     * Returns the incoming connection of the node with the passed node id at
     * the specified port.
     * @param id id of the node of interest
     * @param portIdx port index
     * @return incoming connection at that port of the given node or null if it
     *     doesn't exist
     */
    public ConnectionContainer getIncomingConnectionFor(final NodeID id,
            final int portIdx) {
        synchronized (m_workflowMutex) {
            Set<ConnectionContainer>inConns = m_workflow.getConnectionsByDest(id);
            if (inConns != null) {
                for (ConnectionContainer cont : inConns) {
                    if (cont.getDestPort() == portIdx) {
                        return cont;
                    }
                }
            }
        }
        return null;
    }

    /** Get all incoming connections for a node.
     * @param id The requested node
     * @return All current incoming connections in a new set.
     * @throws IllegalArgumentException If the node is unknown or null.
     */
    public Set<ConnectionContainer> getIncomingConnectionsFor(final NodeID id) {
        synchronized (m_workflowMutex) {
            getNodeContainer(id); // for exception handling
            return new LinkedHashSet<ConnectionContainer>(
                    m_workflow.getConnectionsByDest(id));
        }
    }

    /**
     * Gets a connection by id.
     * @param id of the connection to return
     * @return the connection with the specified id
     */
    public ConnectionContainer getConnection(final ConnectionID id) {
        synchronized (m_workflowMutex) {
            return getIncomingConnectionFor(id.getDestinationNode(),
                    id.getDestinationPort());
        }
    }

    /** Get information on input ports of the argument (meta) node. It's used
     * by the routines that allow the user to change the port information
     * (add, delete, move).
     * @param metaNodeID The argument node
     * @return the meta node's port info.
     * @throws IllegalArgumentException If the node is invalid.
     * @since 2.6 */
    public MetaPortInfo[] getMetanodeInputPortInfo(final NodeID metaNodeID) {
        synchronized (m_workflowMutex) {
            return m_workflow.getMetanodeInputPortInfo(metaNodeID);
        }
    }

    /** Get information on output ports of the argument (meta) node. Similar
     * to {@link #getMetanodeInputPortInfo(NodeID)}.
     * @param metaNodeID ...
     * @return ...
     * @throws IllegalArgumentException If the node is invalid.
     * @since 2.6 */
    public MetaPortInfo[] getMetanodeOutputPortInfo(final NodeID metaNodeID) {
        synchronized (m_workflowMutex) {
            return m_workflow.getMetanodeOutputPortInfo(metaNodeID);
        }
    }

    /** Get information on input ports of the argument (sub) node. It's used
     * by the routines that allow the user to change the port information
     * (add, delete, move).
     * @param subNodeID The argument node
     * @return the sub node's port info.
     * @throws IllegalArgumentException If the node is invalid.
     * @since 2.10 */
    public MetaPortInfo[] getSubnodeInputPortInfo(final NodeID subNodeID) {
        synchronized (m_workflowMutex) {
            return getNodeContainer(subNodeID, SubNodeContainer.class, true).getInputPortInfo();
        }
    }

    /** Get information on output ports of the argument (sub) node. Similar
     * to {@link #getSubnodeInputPortInfo(NodeID)}.
     * @param subNodeID ...
     * @return ...
     * @throws IllegalArgumentException If the node is invalid.
     * @since 2.10 */
    public MetaPortInfo[] getSubnodeOutputPortInfo(final NodeID subNodeID) {
        synchronized (m_workflowMutex) {
            return getNodeContainer(subNodeID, SubNodeContainer.class, true).getOutputPortInfo();
        }
    }

    /**
     * @param subFlowID ID of the subflow
     * @param newPorts The new ports
     * @since 2.6
     */
    public void changeMetaNodeInputPorts(final NodeID subFlowID, final MetaPortInfo[] newPorts) {
        synchronized (m_workflowMutex) {
            WorkflowManager subFlowMgr = getNodeContainer(subFlowID, WorkflowManager.class, true);
            if (!haveMetaPortsChanged(newPorts, true, subFlowMgr)) {
                return;
            }
            List<Pair<ConnectionContainer, ConnectionContainer>> changedConnectionsThisFlow =
                m_workflow.changeDestinationPortsForMetaNode(subFlowID, newPorts, false);
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsThisFlow) {
                ConnectionContainer old = p.getFirst();
                m_workflow.removeConnection(old);
                notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_REMOVED, null, old, null));
            }

            Workflow subFlow = subFlowMgr.m_workflow;
            List<Pair<ConnectionContainer, ConnectionContainer>> changedConnectionsSubFlow =
                subFlow.changeSourcePortsForMetaNode(subFlowID, newPorts, false);
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsSubFlow) {
                ConnectionContainer old = p.getFirst();
                subFlow.removeConnection(old);
                subFlowMgr.notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_REMOVED, null, old, null));
            }

            WorkflowInPort[] newMNPorts = new WorkflowInPort[newPorts.length];
            for (int i = 0; i < newPorts.length; i++) {
                final int oldIndex = newPorts[i].getOldIndex();
                if (oldIndex >= 0) {
                    newMNPorts[i] = subFlowMgr.getInPort(oldIndex);
                    newMNPorts[i].setPortIndex(i);
                } else {
                    newMNPorts[i] = new WorkflowInPort(i, newPorts[i].getType());
                }
            }
            subFlowMgr.m_inPorts = newMNPorts;
            subFlowMgr.notifyNodePropertyChangedListener(NodeProperty.MetaNodePorts);

            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsThisFlow) {
                ConnectionContainer newConn = p.getSecond();
                m_workflow.addConnection(newConn);
                notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_ADDED, null, null, newConn));
            }
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsSubFlow) {
                ConnectionContainer newConn = p.getSecond();
                subFlow.addConnection(newConn);
                subFlowMgr.notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_ADDED, null, null, newConn));
            }
            subFlowMgr.setDirty();
            setDirty();
        }
    }

    /**
     * @param subFlowID ID of the subflow
     * @param newPorts The new ports
     * @since 2.6
     */
    public void changeMetaNodeOutputPorts(final NodeID subFlowID, final MetaPortInfo[] newPorts) {
        synchronized (m_workflowMutex) {
            WorkflowManager subFlowMgr = getNodeContainer(subFlowID, WorkflowManager.class, true);
            if (!haveMetaPortsChanged(newPorts, false, subFlowMgr)) {
                return;
            }
            List<Pair<ConnectionContainer, ConnectionContainer>> changedConnectionsThisFlow =
                m_workflow.changeSourcePortsForMetaNode(subFlowID, newPorts, false);
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsThisFlow) {
                ConnectionContainer old = p.getFirst();
                m_workflow.removeConnection(old);
                notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_REMOVED, null, old, null));
            }

            Workflow subFlow = subFlowMgr.m_workflow;
            List<Pair<ConnectionContainer, ConnectionContainer>> changedConnectionsSubFlow =
                subFlow.changeDestinationPortsForMetaNode(subFlowID, newPorts, false);
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsSubFlow) {
                ConnectionContainer old = p.getFirst();
                subFlow.removeConnection(old);
                subFlowMgr.notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_REMOVED, null, old, null));
            }

            WorkflowOutPort[] newMNPorts = new WorkflowOutPort[newPorts.length];
            for (int i = 0; i < newPorts.length; i++) {
                final int oldIndex = newPorts[i].getOldIndex();
                if (oldIndex >= 0) {
                    newMNPorts[i] = subFlowMgr.getOutPort(oldIndex);
                    newMNPorts[i].setPortIndex(i);
                } else {
                    newMNPorts[i] = new WorkflowOutPort(i, newPorts[i].getType());
                }
            }
            subFlowMgr.m_outPorts = newMNPorts;
            subFlowMgr.notifyNodePropertyChangedListener(NodeProperty.MetaNodePorts);

            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsThisFlow) {
                ConnectionContainer newConn = p.getSecond();
                m_workflow.addConnection(newConn);
                notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_ADDED, null, null, newConn));
            }
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsSubFlow) {
                ConnectionContainer newConn = p.getSecond();
                subFlow.addConnection(newConn);
                subFlowMgr.notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_ADDED, null, null, newConn));
            }
            subFlowMgr.setDirty();
        }
    }

    /**
     * @param subFlowID ID of the subflow
     * @param newPorts The new ports
     * @since 2.10
     */
    public void changeSubNodeInputPorts(final NodeID subFlowID, final MetaPortInfo[] newPorts) {
        synchronized (m_workflowMutex) {
            SubNodeContainer snc = getNodeContainer(subFlowID, SubNodeContainer.class, true);
            if (!haveSubPortsChanged(newPorts, true, snc)) {
                return;
            }
            List<Pair<ConnectionContainer, ConnectionContainer>> changedConnectionsThisFlow =
                m_workflow.changeDestinationPortsForMetaNode(subFlowID, newPorts, true);
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsThisFlow) {
                ConnectionContainer old = p.getFirst();
                m_workflow.removeConnection(old);
                notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_REMOVED, null, old, null));
            }
            Workflow subFlow = snc.getWorkflowManager().m_workflow;
            List<Pair<ConnectionContainer, ConnectionContainer>> changedConnectionsSubFlow =
                subFlow.changeSourcePortsForMetaNode(snc.getVirtualInNodeID(), newPorts, true);
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsSubFlow) {
                ConnectionContainer old = p.getFirst();
                subFlow.removeConnection(old);
                snc.getWorkflowManager().notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_REMOVED, null, old, null));
            }
            PortType[] portTypes = new PortType[newPorts.length - 1];
            for (int i = 0; i < newPorts.length - 1; i++) {
                portTypes[i] = newPorts[i + 1].getType();
            }
            snc.setInPorts(portTypes);
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsThisFlow) {
                ConnectionContainer newConn = p.getSecond();
                m_workflow.addConnection(newConn);
                resetAndConfigureNode(newConn.getDest());
                notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_ADDED, null, null, newConn));
            }
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsSubFlow) {
                ConnectionContainer newConn =
                    new ConnectionContainer(snc.getVirtualInNodeID(), p.getSecond().getSourcePort(), p.getSecond()
                        .getDest(), p.getSecond().getDestPort(), p.getSecond().getType());
                subFlow.addConnection(newConn);
                snc.getWorkflowManager().resetAndConfigureNode(newConn.getDest());
                snc.getWorkflowManager().notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_ADDED, null, null, newConn));
            }
            setDirty();
        }
    }

    /**
     * @param subFlowID ID of the subflow
     * @param newPorts The new ports
     * @since 2.10
     */
    public void changeSubNodeOutputPorts(final NodeID subFlowID, final MetaPortInfo[] newPorts) {
        synchronized (m_workflowMutex) {
            SubNodeContainer snc = getNodeContainer(subFlowID, SubNodeContainer.class, true);
            if (!haveSubPortsChanged(newPorts, false, snc)) {
                return;
            }
            List<Pair<ConnectionContainer, ConnectionContainer>> changedConnectionsThisFlow =
                m_workflow.changeSourcePortsForMetaNode(subFlowID, newPorts, true);
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsThisFlow) {
                ConnectionContainer old = p.getFirst();
                m_workflow.removeConnection(old);
                notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_REMOVED, null, old, null));
            }
            Workflow subFlow = snc.getWorkflowManager().m_workflow;
            List<Pair<ConnectionContainer, ConnectionContainer>> changedConnectionsSubFlow =
                subFlow.changeDestinationPortsForMetaNode(snc.getVirtualOutNodeID(), newPorts, true);
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsSubFlow) {
                ConnectionContainer old = p.getFirst();
                subFlow.removeConnection(old);
                snc.getWorkflowManager().notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_REMOVED, null, old, null));
            }
            PortType[] portTypes = new PortType[newPorts.length - 1];
            for (int i = 0; i < newPorts.length - 1; i++) {
                portTypes[i] = newPorts[i + 1].getType();
            }
            snc.setOutPorts(portTypes);
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsThisFlow) {
                ConnectionContainer newConn = p.getSecond();
                m_workflow.addConnection(newConn);
                resetAndConfigureNode(newConn.getDest());
                notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_ADDED, null, null, newConn));
            }
            for (Pair<ConnectionContainer, ConnectionContainer> p : changedConnectionsSubFlow) {
                ConnectionContainer newConn =
                    new ConnectionContainer(p.getSecond().getSource(), p.getSecond().getSourcePort(),
                        snc.getVirtualOutNodeID(), p.getSecond().getDestPort(), p.getSecond().getType());
                subFlow.addConnection(newConn);
                snc.getWorkflowManager().resetAndConfigureNode(newConn.getDest());
                snc.getWorkflowManager().notifyWorkflowListeners(new WorkflowEvent(
                        WorkflowEvent.Type.CONNECTION_ADDED, null, null, newConn));
            }
            setDirty();
        }
    }

    /**
     * @param newPorts
     * @param inPorts if true, otherwise outports
     * @param subFlowMgr */
    private static boolean haveMetaPortsChanged(final MetaPortInfo[] newPorts, final boolean inPorts,
            final WorkflowManager subFlowMgr) {
        if (newPorts.length != (inPorts ? subFlowMgr.getNrInPorts() : subFlowMgr.getNrOutPorts())) {
            return true;
        }
        for (int i = 0; i < newPorts.length; i++) {
            if (newPorts[i].getOldIndex() != newPorts[i].getNewIndex()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param newPorts
     * @param inPorts if true, otherwise outports
     * @param subFlowMgr */
    private static boolean haveSubPortsChanged(final MetaPortInfo[] newPorts, final boolean inPorts,
            final SubNodeContainer snc) {
        int ports =
            inPorts ? snc.getWorkflowManager().getNodeContainer(snc.getVirtualInNodeID()).getNrOutPorts() : snc
                .getWorkflowManager().getNodeContainer(snc.getVirtualOutNodeID()).getNrInPorts();
        if (newPorts.length != ports) {
            return true;
        }
        for (int i = 0; i < newPorts.length; i++) {
            if (newPorts[i].getOldIndex() != newPorts[i].getNewIndex()) {
                return true;
            }
        }
        return false;
    }

    /////////////////////
    // Node Settings
    /////////////////////

    /** Load Settings into specified node.
     *
     * @param id of node
     * @param settings to be load by node
     * @throws InvalidSettingsException if settings are wrong
     * @throws IllegalArgumentException if node does not exist
     */
    public void loadNodeSettings(final NodeID id, final NodeSettingsRO settings)
    throws InvalidSettingsException {
        synchronized (m_workflowMutex) {
            NodeContainer nc = getNodeContainer(id);
            if (!nc.getInternalState().isExecutionInProgress() && !hasSuccessorInProgress(id)) {
                // make sure we are consistent (that is reset + configure)
                // if we touch upstream nodes implicitly (e.g. loop heads)
                nc.validateSettings(settings);
                resetNodeAndSuccessors(id);
                nc.loadSettings(settings);
                // bug fix 2593: can't simply call configureNodeAndSuccessor
                // with meta node as argument: will miss contained source nodes
                if (nc instanceof SingleNodeContainer) {
                    configureNodeAndSuccessors(id, true);
                } else {
                    ((WorkflowManager)nc).reconfigureAllNodesOnlyInThisWFM();
                    configureNodeAndSuccessors(id, false);
                }
            } else {
                throw new IllegalStateException(
                    "Cannot load settings into node; it is executing or has executing successors");
            }
        }
    }

    /**
     * write node settings into Settings object.
     *
     * @param id of node
     * @param settings to be saved to
     * @throws InvalidSettingsException thrown if nonsense is written
     */
    public void saveNodeSettings(final NodeID id, final NodeSettingsWO settings)
    throws InvalidSettingsException {
        synchronized (m_workflowMutex) {
            NodeContainer nc = getNodeContainer(id);
            nc.saveSettings(settings);
        }
    }

    /** Gets for a set of nodes their (overlapping) node settings. This is currently only the job manager but
     * might contain also the memory settings in the future. If the nodes have different settings (e.g. job managers),
     * the result will represent a default (e.g. a null job manager).
     *
     * <p>Used from a GUI action that allows the user to modify the settings for multiple selected nodes.
     * @param ids The nodes of interest.
     * @return The settings ... as far as they overlap
     * @noreference This method is not intended to be referenced by clients.
     * @since 2.7
     */
    public NodeContainerSettings getCommonSettings(final NodeID... ids) {
        synchronized (m_workflowMutex) {
            NodeExecutionJobManager[] jobManagers = new NodeExecutionJobManager[ids.length];
            for (int i = 0; i < ids.length; i++) {
                NodeContainer nc = getNodeContainer(ids[i]);
                jobManagers[i] = nc.getJobManager();
            }
            return NodeExecutionJobManagerPool.merge(jobManagers);
        }
    }

    /** Counterpart to {@link #getCommonSettings(NodeID...)}. It applies the same settings to all
     * argument nodes.
     * @param settings ...
     * @param ids ...
     * @throws InvalidSettingsException If not possible (settings may be applied to half of the nodes)
     * @since 2.7
     */
    public void applyCommonSettings(final NodeContainerSettings settings, final NodeID... ids)
        throws InvalidSettingsException {
        synchronized (m_workflowMutex) {
            for (NodeID id : ids) {
                NodeContainer nc = getNodeContainer(id);
                if (nc.getInternalState().isExecutionInProgress() || hasSuccessorInProgress(id)) {
                    throw new IllegalStateException("Cannot load settings into node \"" + nc.getNameWithID()
                                                    + "\"; it is executing or has executing successors");
                }
            }
            for (NodeID id : ids) {
                NodeContainer nc = getNodeContainer(id);
                // make sure we are consistent (that is reset + configure)
                // if we touch upstream nodes implicitly (e.g. loop heads)
                resetNodeAndSuccessors(id);
                nc.loadCommonSettings(settings);
                // bug fix 2593: can't simply call configureNodeAndSuccessor
                // with meta node as argument: will miss contained source nodes
                if (nc instanceof SingleNodeContainer) {
                    configureNodeAndSuccessors(id, true);
                } else {
                    ((WorkflowManager)nc).reconfigureAllNodesOnlyInThisWFM();
                    configureNodeAndSuccessors(id, false);
                }
            }

        }
    }

    ////////////////////////////
    // Execution of nodes
    ////////////////////////////

    /** (un)mark all nodes in the workflow (and all subworkflows!)
     * for execution (if they are not executed already). Once they are
     * configured (if not already) and all inputs are available they
     * will be queued automatically (usually triggered by doAfterExecution
     * of the predecessors).
     * This does NOT affect any predecessors of this workflow!
     *
     * @param flag mark nodes if true, otherwise try to erase marks
     */
    private void markForExecutionAllNodesInWorkflow(final boolean flag) {
        synchronized (m_workflowMutex) {
            boolean changed = false; // will be true in case of state changes
            for (NodeContainer nc : m_workflow.getNodeValues()) {
                if (nc instanceof SingleNodeContainer) {
                    SingleNodeContainer snc = (SingleNodeContainer)nc;
                    if (flag) {
                        switch (nc.getInternalState()) {
                        case CONFIGURED:
                        case IDLE:
                            changed = true;
                            snc.markForExecution(true);
                            break;
                        default:
                            // either executed or to-be-executed
                        }

                    } else {
                        switch (nc.getInternalState()) {
                        case EXECUTED_MARKEDFOREXEC:
                        case CONFIGURED_MARKEDFOREXEC:
                        case UNCONFIGURED_MARKEDFOREXEC:
                            changed = true;
                            snc.markForExecution(false);
                            break;
                        default:
                            // ignore all other
                        }
                    }
                } else {
                    WorkflowManager wfm = ((WorkflowManager)nc);
                    // does not need to set "changed" flag here as this child
                    // will propagate state changes by calling
                    // call checkForNodeStateChanges (possibly too often)
                    wfm.markForExecutionAllNodesInWorkflow(flag);
                }
            }
            if (changed) {
                checkForNodeStateChanges(true);
            }
        }
    }

    /**
     * Mark all nodes in this workflow that are connected to the given
     * inports.
     * Note that this routine will NOT trigger any actions connected to
     * possible outports of this WFM.
     *
     * @param inPorts set of port indices of the WFM.
     * @param markExecutedNodes if true also (re)mark executed nodes.
     */
    void markForExecutionNodesInWFMConnectedToInPorts(final Set<Integer> inPorts, final boolean markExecutedNodes) {
        synchronized (m_workflowMutex) {
            boolean changed = false; // will be true in case of state changes
            ArrayList<NodeAndInports> nodes = m_workflow.findAllConnectedNodes(inPorts);
            for (NodeAndInports nai : nodes) {
                NodeContainer nc = m_workflow.getNode(nai.getID());
                if (nc instanceof SingleNodeContainer) {
                    SingleNodeContainer snc = (SingleNodeContainer)nc;
                    switch (nc.getInternalState()) {
                    case CONFIGURED:
                    case IDLE:
                        changed = true;
                        snc.markForExecution(true);
                        break;
                    case EXECUTED:
                        if (markExecutedNodes) {
                            // in case of loop bodies that asked not to be reset.
                            changed = true;
                            snc.markForReExecution(new ExecutionEnvironment(true, null, false));
                            break;
                        }
                    default:
                        // either executed or to-be-executed
                    }
                } else {
                    WorkflowManager wfm = ((WorkflowManager)nc);
                    assert nc instanceof WorkflowManager;
                    // does not need to set "changed" flag here as child
                    // will propagate state changes by calling
                    // call checkForNodeStateChanges (likely too often)
                    wfm.markForExecutionNodesInWFMConnectedToInPorts(nai.getInports(), markExecutedNodes);
                }
            }
            if (changed) {
                checkForNodeStateChanges(true);
            }
        }
    }

    /** mark all nodes connected to the specified outport(!)
     * in this workflow (and all subworkflows!) for execution
     * (if they are not executed already). Also go back up to the
     * predecessors of this wfm if there are connections of interest.
     *
     * @param outPortIndex indicates which outport is affected
     *   (-1 for all outports)
     * @return true if all nodes in chain were markable
     */
    private boolean markForExecutionAllAffectedNodes(final int outPortIndex) {
        synchronized (m_workflowMutex) {
            HashSet<Integer> p = new HashSet<Integer>();
            if (outPortIndex >= 0) {
                p.add(outPortIndex);
            } else {
                // if all ports are to be used, add them explicitly
                for (int o = 0; o < this.getNrOutPorts(); o++) {
                    p.add(o);
                }
            }
            LinkedHashMap<NodeID, Set<Integer>> sortedNodes =
                m_workflow.createBackwardsBreadthFirstSortedList(p);
            for (Map.Entry<NodeID, Set<Integer>> entry : sortedNodes.entrySet()) {
                final NodeID thisID = entry.getKey();
                if (thisID.equals(getID())) {
                    continue; // skip WFM
                }
                NodeContainer thisNode = m_workflow.getNode(thisID);
                if (thisNode instanceof SingleNodeContainer) {
                    SingleNodeContainer snc = (SingleNodeContainer)thisNode;
                    switch (snc.getInternalState()) {
                    case IDLE:
                    case CONFIGURED:
                        if (!markAndQueueNodeAndPredecessors(snc.getID(), -1)) {
                            return false;
                        }
                        break;
                    case EXECUTED_MARKEDFOREXEC:
                    case CONFIGURED_MARKEDFOREXEC:
                    case UNCONFIGURED_MARKEDFOREXEC:
                        // tolerate those states - nodes are already marked.
                        break;
                    default: // already running
                        // TODO other states. Any reason to bomb?
                    }
                } else {
                    assert thisNode instanceof WorkflowManager;
                    Set<Integer> outPortIndicces = entry.getValue();
                    for (Integer i : outPortIndicces) {
                        if (!((WorkflowManager)thisNode).
                                markForExecutionAllAffectedNodes(i)) {
                            return false;
                        }
                    }
                }
            } // endfor all nodes in sorted list
            checkForNodeStateChanges(true);
            if (sortedNodes.containsKey(this.getID())) {
                // list contained WFM, go up one level
                Set<Integer> is = sortedNodes.get(this.getID());
                for (Integer i : is) {
                    getParent().markAndQueuePredecessors(this.getID(), i);
                }
            }
        }
        return true;
    }

    /** Called when a loop must be aborted because some (nested) loop body element failed.
     * @param loopContext The context to find the corresponding loop end node.
     * @param message A message or null to set as error on the end node. */
    private void disableLoopExecution(final FlowLoopContext loopContext, final NodeMessage message) {
        synchronized (m_workflowMutex) {
            final NodeID endNode = loopContext.getTailNode();
            if (message != null) {
                getNodeContainer(endNode).setNodeMessage(message);
            }
            disableNodeForExecution(endNode);
        }
    }

    /**
     * Reset the mark when nodes have been set to be executed. Skip
     * nodes which are already queued or executing.
     *
     * Note: we assume this is wrapped in calls which updates the metanode status.
     *
     * @param id ...
     */
    private void disableNodeForExecution(final NodeID id) {
        disableNodeForExecution(id, -1);
    }

    /**
     * Reset the mark when nodes have been set to be executed. Skip
     * nodes which are already queued or executing.
     *
     * @param inport index or -1 in case of an SNC
     * @param snc
     */
    private void disableNodeForExecution(final NodeID id, final int inportIndex) {
        NodeContainer nc = m_workflow.getNode(id);
        if (nc != null) {
            switch (nc.getInternalState()) {
            case IDLE:
            case CONFIGURED:
                if (nc instanceof SingleNodeContainer) {
                    // nothing needs to be done - also with the successors!
                    return;
                }
                // in not-SNC case do check successor (could be a through-conn)
            case EXECUTED_MARKEDFOREXEC:
            case CONFIGURED_MARKEDFOREXEC:
            case UNCONFIGURED_MARKEDFOREXEC:
                if (nc instanceof SingleNodeContainer) {
                    ((SingleNodeContainer)nc).markForExecution(false);
                    disableSuccessorsForExecution(id, -1);
                    break;
                }
                assert !(nc instanceof SingleNodeContainer);
                // (treat MARKED and EXECUTING metanodes the same - both can contain MARKED nodes!)
            case EXECUTING:
                // bug "fix" (workaround) 3913
                // this is only relevant for WorkflowManagers (it may still contain some MARKED nodes)
                if (!(nc instanceof SingleNodeContainer)) {
                    if (inportIndex == -1) {
                        ((WorkflowManager)nc).markForExecutionAllNodesInWorkflow(false);
                        disableSuccessorsForExecution(id, -1);
                    } else {
                        // automatically unmarks connected successors of this metanode/port as well.
                        ((WorkflowManager)nc).disableInportSuccessorsForExecution(inportIndex);
                    }
                }
                break;
            default:
                // ignore all other states but disable successors!
                if (nc instanceof SingleNodeContainer) {
                    disableSuccessorsForExecution(id, -1);
                } else {
                    if (inportIndex == -1) {
                        ((WorkflowManager)nc).markForExecutionAllNodesInWorkflow(false);
                        disableSuccessorsForExecution(id, -1);
                    } else {
                        ((WorkflowManager)nc).disableInportSuccessorsForExecution(inportIndex);
                    }
                }
            }
        } else { // WFM
            assert getID().equals(id);
            if (inportIndex == -1) {
                this.markForExecutionAllNodesInWorkflow(false);
                // unmark successors (on all ports) of this metanode
                getParent().disableSuccessorsForExecution(this.getID(), -1);
            } else {
                // automatically unmarks connected successors of this metanode as well.
                this.disableSuccessorsForExecution(id, inportIndex);
                checkForNodeStateChanges(true);
            }
        }
    }

    /** Disable (unmark!) all successors of the given node connected to the given outport.
    *
    * @param id the node
    * @param outportIndex the node's outport (-1 for all)
    */
    private void disableSuccessorsForExecution(final NodeID id, final int outportIndex) {
        assert !this.getID().equals(id);
        if (hasSuccessorInProgress(id)) {
            // is it a node inside this wfm with successors worth exploring
            for (ConnectionContainer cc : m_workflow.getConnectionsBySource(id)) {
                if ((outportIndex) == -1 || (cc.getSourcePort() == outportIndex)) {
                    NodeID succId = cc.getDest();
                    if (succId.equals(this.getID())) {
                        // unmark successors of this metanode
                        getParent().disableSuccessorsForExecution(this.getID(), cc.getDestPort());
                    } else {
                        // handle normal node
                        disableNodeForExecution(succId, cc.getDestPort());
                    }
                }
            }
        }
    }

    /** Disable (unmark!) all successors of the given input port if this WFM.
     *
     * @param inportIndex this node's inport
     */
   private void disableInportSuccessorsForExecution(final int inportIndex) {
       for (ConnectionContainer cc : m_workflow.getConnectionsBySource(this.getID())) {
           if ((inportIndex) == -1 || (cc.getSourcePort() == inportIndex)) {
               NodeID succId = cc.getDest();
               if (succId.equals(this.getID())) {
                   // unmark successors of this metanode
                   getParent().disableSuccessorsForExecution(this.getID(), cc.getDestPort());
               } else {
                   // handle normal node
                   disableNodeForExecution(succId, cc.getDestPort());
               }
           }
       }
       checkForNodeStateChanges(false);
   }

    /**
     * Reset all nodes in this workflow. Make sure the reset is propagated
     * in the right order, that is, only actively reset the "left most"
     * nodes in the workflow or the ones connected to meta node input
     * ports. The will also trigger resets of subsequent nodes.
     * Also re-configure not executed nodes the same way to make sure that
     * new workflow variables are spread accordingly.
     */
//    public void resetAll() {
//        synchronized (m_workflowMutex) {
//            for (NodeID id : m_workflow.getNodeIDs()) {
//                boolean hasNonParentPredecessors = false;
//                for (ConnectionContainer cc
//                        : m_workflow.getConnectionsByDest(id)) {
//                    if (!cc.getSource().equals(this.getID())) {
//                        hasNonParentPredecessors = true;
//                        break;
//                    }
//                }
//                if (!hasNonParentPredecessors) {
//                    if (getNodeContainer(id).isResetable()) {
//                        // reset nodes which are green - will configure
//                        // them afterwards anyway.
//                        resetAndConfigureNode(id);
//                    } else {
//                        // but make sure to re-configure yellow nodes so
//                        // that new variables are available in those
//                        // pipeline branches!
//                        configureNodeAndSuccessors(id, true);
//                    }
//                }
//            }
//        }
//    }

    /**
     * Re-configure all configured (NOT executed) nodes in this workflow
     * to make sure that new workflow variables are spread accordingly.
     * Note that this does NOT affect any successors of this workflow
     * manager but touches all nodes inside this wfm and its kids.
     */
    void reconfigureAllNodesOnlyInThisWFM() {
        synchronized (m_workflowMutex) {
            // do not worry about pipelines, just process all nodes "left
            // to right" and make sure we touch all of them (also yellow
            // nodes in a metanode that is connected to red nodes only)...
            for (NodeID sortedID : m_workflow.createBreadthFirstSortedList(
                     m_workflow.getNodeIDs(), true).keySet()) {
                NodeContainer nc = getNodeContainer(sortedID);
                if (nc instanceof SingleNodeContainer) {
                    // reconfigure yellow AND red nodes - it could be that
                    // the reason for the red state were the variables!
                    if (nc.getInternalState().equals(InternalNodeContainerState.CONFIGURED)
                            || nc.getInternalState().equals(InternalNodeContainerState.IDLE)) {
                        configureSingleNodeContainer(
                            (SingleNodeContainer)nc,
                            /* keepNodemessage=*/ false);
                    }
                } else {
                    assert nc instanceof WorkflowManager;
                    ((WorkflowManager)nc).reconfigureAllNodesOnlyInThisWFM();
                }
            }
            checkForNodeStateChanges(true);
        }
    }

    /**
     * Reset all executed nodes in this workflow to make sure that new
     * workflow variables are spread accordingly. If a node is already
     * reset (or we just reset it), also configure it.
     * Note that this does NOT affect any successors of this workflow
     * manager but touches all nodes inside this wfm and its kids.
     *
     * TODO Maybe redundant: call resetAllNodes... and then configure them
     *  (only called when WorkflowVariables are set)
     */
    void resetAndReconfigureAllNodesInWFM() {
        synchronized (m_workflowMutex) {
            // do not worry about pipelines, just process all nodes "left
            // to right" and make sure we touch all of them (also yellow/green
            // nodes in a metanode that is connected to red nodes only)...
            for (NodeID sortedID : m_workflow.createBreadthFirstSortedList(m_workflow.getNodeIDs(), true).keySet()) {
                // TODO reset all nodes in reverse order first
                NodeContainer nc = getNodeContainer(sortedID);
                if (nc instanceof SingleNodeContainer) {
                    // (reset) and configure yellow AND red nodes - it could be
                    // that the reason for the red state were the variables!
                    if (nc.isResetable()) {
                        ((SingleNodeContainer)nc).reset();
                    }
                    if (nc.getInternalState().equals(InternalNodeContainerState.CONFIGURED)
                            || nc.getInternalState().equals(InternalNodeContainerState.IDLE)) {
                        // re-configure if node was yellow or we just reset it.
                        // note that there still may be metanodes
                        // connected to this one which contain green
                        // nodes! (hence the brute force left-to-right approach
                        configureSingleNodeContainer((SingleNodeContainer)nc, /* keepNodemessage=*/ false);
                    }
                } else {
                    assert nc instanceof WorkflowManager;
                    ((WorkflowManager)nc).resetAndReconfigureAllNodesInWFM();
                }
            }
            checkForNodeStateChanges(true);
        }
    }

    /** Resets and freshly configures all nodes in this workflow.
     * @deprecated Use {@link #resetAndConfigureAll()} instead
     */
    @Deprecated
    public void resetAll() {
        resetAndConfigureAll();
    }

    /** Resets and freshly configures all nodes in this workflow. */
    public void resetAndConfigureAll() {
        // TODO this does not reset connected outports (which it should as this
        // is a public methods. (see resetAndReconfigureAllNodesInWFM)
        resetAndReconfigureAllNodesInWFM();
    }

    /**
     * Reset all nodes in this workflow.
     * Note that this routine will NOT trigger any resets connected to
     * possible outports of this WFM.
     */
    void resetAllNodesInWFM() {
        synchronized (m_workflowMutex) {
            if (!isResetable()) {
                // only attempt to do this if possible.
                return;
            }
            for (NodeID id : m_workflow.getNodeIDs()) {
                // we don't need to worry about the correct order since
                // we will reset everything in here anyway.
                NodeContainer nc = m_workflow.getNode(id);
                if (nc.isResetable()) {
                    if (nc instanceof SingleNodeContainer) {
                        ((SingleNodeContainer)nc).reset();
                    } else {
                        assert nc instanceof WorkflowManager;
                        ((WorkflowManager)nc).resetAllNodesInWFM();
                    }
                }
            }
            // TODO Michael: this can be replaced by checkForNodeState...
            //
            // don't let the WFM decide on the state himself - for example,
            // if there is only one WFMTHROUGH connection contained, it will
            // produce wrong states! Force it to be idle.
            setInternalState(InternalNodeContainerState.IDLE);
        }
    }

    /**
     * Reset all nodes in this workflow that are connected to the given
     * inports. The reset is performed in the correct order, that is last
     * nodes are reset first.
     * Note that this routine will NOT trigger any resets connected to
     * possible outports of this WFM.
     *
     * @param inPorts set of port indices of the WFM.
     */
    void resetNodesInWFMConnectedToInPorts(final Set<Integer> inPorts) {
        synchronized (m_workflowMutex) {
            if (!isResetable()) {
                // only attempt to do this if possible.
                return;
            }
            // first make sure we clean up indirectly affected
            // loop start nodes inside this WFM
            resetAndConfigureAffectedLoopContext(this.getID(), inPorts);
            // now find all nodes that are directly affected:
            ArrayList<NodeAndInports> nodes = m_workflow.findAllConnectedNodes(inPorts);
            ListIterator<NodeAndInports> li = nodes.listIterator(nodes.size());
            while (li.hasPrevious()) {
                NodeAndInports nai = li.previous();
                NodeContainer nc = m_workflow.getNode(nai.getID());
                if (nc.isResetable()) {
                    if (nc instanceof SingleNodeContainer) {
                        // reset node
                        ((SingleNodeContainer)nc).reset();
                    } else {
                        assert nc instanceof WorkflowManager;
                        ((WorkflowManager)nc).resetNodesInWFMConnectedToInPorts(nai.getInports());
                    }
                }
            }
            checkForNodeStateChanges(false);
        }
    }

    /**
     * Clean outports of nodes connected to set of input ports. Used while
     * restarting the loop, whereby the loop body is not to be reset (special
     * option in start nodes). Clearing is done in correct order: downstream
     * nodes first.
     * @param inPorts set of port indices of the WFM.
     */
    void cleanOutputPortsInWFMConnectedToInPorts(final Set<Integer> inPorts) {
        synchronized (m_workflowMutex) {
            ArrayList<NodeAndInports> nodes =
                m_workflow.findAllConnectedNodes(inPorts);
            ListIterator<NodeAndInports> li = nodes.listIterator(nodes.size());
            while (li.hasPrevious()) {
                NodeAndInports nai = li.previous();
                NodeContainer nc = m_workflow.getNode(nai.getID());
                if (nc.isResetable()) {
                    if (nc instanceof SingleNodeContainer) {
                        ((SingleNodeContainer)nc).cleanOutPorts(true);
                    } else {
                        assert nc instanceof WorkflowManager;
                        ((WorkflowManager)nc).cleanOutputPortsInWFMConnectedToInPorts(nai.getInports());
                    }
                }
            }
        }
    }

    /** mark these nodes and all not-yet-executed predecessors for execution.
     * They will be marked first, queued when all inputs are available and
     * finally executed.
     *
     * @param ids node ids to mark
     */
    public void executeUpToHere(final NodeID... ids) {
        synchronized (m_workflowMutex) {
            for (NodeID id : ids) {
                NodeContainer nc = getNodeContainer(id);
                if (nc instanceof SingleNodeContainer) {
                    markAndQueueNodeAndPredecessors(id, -1);
                } else if (nc.isLocalWFM()) {
                    // if the execute option on a meta node is selected, run
                    // all nodes in it, not just the ones that are connected
                    // to the outports
                    // this will also trigger an execute on predecessors
                    ((WorkflowManager)nc).executeAll();
                } else {
                    markAndQueueNodeAndPredecessors(id, -1);
                }
            }
            checkForNodeStateChanges(true);
        }
    }

    /**
     * @param id ...
     * @return true if node can be re-executed.
     * @throws IllegalArgumentException if node is not of proper type.
     * @since 2.8
     */
    public boolean canReExecuteNode(final NodeID id) {
        synchronized (m_workflowMutex) {
            NodeContainer nc = getNodeContainer(id);
            if (!(nc instanceof NativeNodeContainer)) {
                throw new IllegalArgumentException("Can't reexecute sub- or metanodes.");
            }
            NativeNodeContainer snc = (NativeNodeContainer)nc;
            NodeModel nm = snc.getNodeModel();
            if (!(nm instanceof InteractiveNode)) {
                throw new IllegalArgumentException("Can't reexecute non interactive nodes.");
            }
            if (!(InternalNodeContainerState.EXECUTED.equals(snc.getInternalState()))) {
                return false;
            }
            if (!canResetNode(id)) {
                return false;
            }
            return true;
        }
    }

    /** Reexecute given node. This required an executed InteractiveNodeModel.
     * Side effects:
     *  - a reset/configure of executed successors.
     *
     * @param id the node
     * @param vc the view content to be loaded into the node before re-execution
     * @param useAsNewDefault true if the view content is to be used as new node settings
     * @param rec callback object for user interaction (do you really want to reset...)
     * @since 2.10
     */
    public void reExecuteNode(final NodeID id, final ViewContent vc, final boolean useAsNewDefault, final ReexecutionCallback rec) {
        synchronized (m_workflowMutex) {
            if (!canReExecuteNode(id)) {
                throw new IllegalArgumentException("Can't reexecute executing nodes.");
            }
            SingleNodeContainer snc = (SingleNodeContainer)getNodeContainer(id);
            resetSuccessors(id);
            configureNodeAndPortSuccessors(id, null, false, true);
            snc.markForReExecution(new ExecutionEnvironment(true, vc, useAsNewDefault));
            assert snc.getInternalState().equals(InternalNodeContainerState.EXECUTED_MARKEDFOREXEC);
            queueIfQueuable(snc);
        }
    }

    /** Called by views of {@link InteractiveNode interactive nodes}. It will take the settings of the NodeModel
     * and save them in the {@link SingleNodeContainerSettings} so that they become the default for the next execution.
     * @param id The node in question.
     * @since 2.8
     */
    public void saveNodeSettingsToDefault(final NodeID id) {
        synchronized (m_workflowMutex) {
            NodeContainer nc = getNodeContainer(id);
            if (!(nc instanceof SingleNodeContainer)) {
                throw new IllegalArgumentException("Can't set new defaults for metanodes.");
            }
            SingleNodeContainer snc = (SingleNodeContainer)nc;
            snc.saveNodeSettingsToDefault();
        }
    }

    private WizardExecutionController m_wizardExecutionController;

    /** Creates lazy and returns an instance that controls the wizard execution of this workflow. These controller
     * are not meant to be used by multiple clients (only one steps back/forth in the workflow), though this is not
     * asserted by the returned controller object.
     * @return A controller for the wizard execution (a new or a previously created and modified instance).
     * @throws IllegalStateException If this workflow is not a project.
     * @since 2.10
     */
    public WizardExecutionController getWizardExecutionController() {
        CheckUtils.checkState(isProject(), "Workflow '%s' is not a project", getNameWithID());
        synchronized (m_workflowMutex) {
            if (m_wizardExecutionController == null) {
                m_wizardExecutionController = new WizardExecutionController(this);
            }
            return m_wizardExecutionController;
        }
    }

    /** Execute workflow until nodes of the given class - those will
     * usually be QuickForm or view nodes requiring user interaction.
     *
     * @param <T> ...
     * @param nodeModelClass the interface of the "stepping" nodes
     * @param filter ...
     * @since 2.7
     */
    public <T> void stepExecutionUpToNodeType(final Class<T> nodeModelClass, final NodeModelFilter<T> filter) {
        stepExecutionUpToNodeType(nodeModelClass, filter, new HashSet<NodeID>());
    }

    /** Implementation of {@link #stepExecutionUpToNodeType(Class, NodeModelFilter)}.
     * @param nodeModelClass ...
     * @param filter ...
     * @param visitedNodes set of nodes which were already seen and either marked or aborted (initially empty)
     *        see bug 41745
     */
    private <T> void stepExecutionUpToNodeType(final Class<T> nodeModelClass,
                                               final NodeModelFilter<T> filter, final Set<NodeID> visitedNodes) {
        synchronized (m_workflowMutex) {
            HashMap<NodeID, Integer> nodes = m_workflow.getStartNodes();
            for (NodeID id : nodes.keySet()) {
                stepExecutionUpToNodeType(id, nodeModelClass, filter, visitedNodes);
            }
            checkForNodeStateChanges(true);
        }
    }

    /** Recursively continue to trigger execution of nodes until first
     * unexecuted node of specified type is encountered.
     *
     * @param NodeID node to start from
     * @param <T> ...
     * @param nodeModelClass the interface of the "stepping" nodes
     */
    private <T> void stepExecutionUpToNodeType(final NodeID id, final Class<T> nodeModelClass,
            final NodeModelFilter<T> filter, final Set<NodeID> visitedNodes) {
        assert Thread.holdsLock(m_workflowMutex); // lock prevents state transitions due to finished executions.
        NodeContainer nc = getNodeContainer(id);
        if (!nc.isLocalWFM()) { // single node container or meta node with other job manager (SGE, DR, ...)
            // for single Node Containers we need to make sure that they are
            // fully connected and all predecessors are executed or executing:
            // 2.7.1.: fixes bug #3976 where we also did that for Metanodes - not
            // all inports need to be connected (or executing/executed)
            // 2.7.2.: caused bug #4149.
            NodeOutPort[] incoming = assemblePredecessorOutPorts(id);
            for (int i = 0; i < incoming.length; i++) {
                if (incoming[i] == null) {
                    if (!nc.getInPort(i).getPortType().isOptional()) {
                        return;  // stop here
                    }
                } else {
                    InternalNodeContainerState state = incoming[i].getNodeState();
                    if (!InternalNodeContainerState.EXECUTED.equals(state) && !state.isExecutionInProgress()) {
                        return;  // stop here
                    }
                }
            }
            if (!visitedNodes.add(id)) {
                // each time we do something real with the node, we add it
                // (makes some of the statements below useless..)
                return;
            }            // node has all required predecessors and they are all marked
            // or executing or executed....
            InternalNodeContainerState state = nc.getInternalState();
            if (InternalNodeContainerState.EXECUTED.equals(state)) {
                // ignore executed nodes and push the step execution downstream
            } else if (state.isExecutionInProgress()) {
                // node has started to execute in the same call to stepExecution -- downstram nodes are taken care of
                return;  // stop here, too! Fixes bug #4175 (new in 2.7.3)
            } else {
                // ...but first check if it's not the stopping type!
                if ((nc instanceof NativeNodeContainer) && !((SingleNodeContainer)nc).isInactive()) {
                    // the node itself is not yet marked/executed - mark it
                    NativeNodeContainer nnc = (NativeNodeContainer)nc;
                    // if current nodeModel is of class nodeModelClass and not filtered
                    if (nodeModelClass.isInstance(nnc.getNodeModel())) {
                        @SuppressWarnings("unchecked")
                        final T nodeModel = (T) nnc.getNodeModel();
                        if (filter.include(nodeModel)) {
                            return;  // stop here
                        }
                    }
                }
                this.markAndQueueNodeAndPredecessors(id, -1);
            }
        } else {
            // fixes bug #4149: for metanodes we can't really check much: there may
            // be unconnected inports, executing metanode containing some not
            // (yet) executing/executed nodes etc.
            // Just step in and check the SNCs inside - for those we know
            // what to do!

            assert nc.isLocalWFM();
            if (!InternalNodeContainerState.EXECUTED.equals(nc.getInternalState())) {
                // if not yet fully executed step inside
                ((WorkflowManager)nc).stepExecutionUpToNodeType(nodeModelClass, filter);
            }
        }
        // and also mark successors
        stepExecutionUpToNodeTypeSuccessorsOnly(id, nodeModelClass, filter, visitedNodes);
    }

    /** Recursively continue to trigger execution of nodes until first
     * unexecuted node of specified type is encountered. This routine
     * only inspects the successors of the given node, see
     * {@link #stepExecutionUpToNodeType(Class)} for the complementary
     * version.
     *
     * @param NodeID node to start from
     * @param <T> ...
     * @param nodeModelClass the interface of the "stepping" nodes
     */
    private <T> void stepExecutionUpToNodeTypeSuccessorsOnly(final NodeID id, final Class<T> nodeModelClass,
            final NodeModelFilter<T> filter, final Set<NodeID> visitedNodes) {
        for (ConnectionContainer cc : m_workflow.getConnectionsBySource(id)) {
            if (!this.getID().equals(cc.getDest())) {
                stepExecutionUpToNodeType(cc.getDest(), nodeModelClass, filter, visitedNodes);
            } else {
                getParent().stepExecutionUpToNodeTypeSuccessorsOnly(cc.getDest(), nodeModelClass, filter, visitedNodes);
            }
        }
    }

    /** Attempts to execute all nodes upstream of the argument node. The method
     * waits (until either all predecessors are executed or there is no further
     * chance to execute anything).
     *
     * @param id The node whose upstream nodes need to be executed.
     * @throws InterruptedException If thread is canceled during waiting
     * (has no affect on the workflow execution).
     * @since 2.6*/
    public void executePredecessorsAndWait(final NodeID id) throws InterruptedException {
        final NodeOutPort[] predecessorOutPorts;
        synchronized (m_workflowMutex) {
            final NodeContainer nc = getNodeContainer(id);
            predecessorOutPorts = assemblePredecessorOutPorts(id);
            boolean hasChanged = false;
            for (int i = 0; i < nc.getNrInPorts(); i++) {
                hasChanged = markAndQueuePredecessors(id, i) || hasChanged;
            }
            if (hasChanged) {
                checkForNodeStateChanges(true);
            }
        }
        waitWhileInExecution(predecessorOutPorts, 0L, TimeUnit.MILLISECONDS);
    }

    /** Find all nodes which are connected to a specific inport of a node
     * and try to mark/queue them.
     *
     * @param id of node
     * @param inPortIndex index of inport (-1 for all -- only used from subnode)
     * @return true of the marking was successful.
     */
    private boolean markAndQueuePredecessors(final NodeID id, final int inPortIndex) {
        assert m_workflow.getNode(id) != null;
        Set<ConnectionContainer> predConn = m_workflow.getConnectionsByDest(id);
        for (ConnectionContainer cc : predConn) {
            NodeID predID = cc.getSource();
            if (inPortIndex == -1 || cc.getDestPort() == inPortIndex) {
                // those are the nodes we are interested in: us as destination
                // and our inport is connected to their outport
                if (predID.equals(this.getID())) {
                    // we are leaving this workflow!
                    if (!getParent().markAndQueuePredecessors(predID, cc.getSourcePort())) {
                        // give up if this "branch" fails
                        return false;
                    }
                } else {
                    assert m_workflow.getNode(predID) != null;
                    // a "normal" node in this Workflow, mark it and its
                    // predecessors
                    if (!markAndQueueNodeAndPredecessors(predID,
                            cc.getSourcePort())) {
                        // give up if this "branch" fails
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /** Recursively iterates the predecessors and marks them for execution
     * before marking (and possibly queuing) this node itself.
     *
     * @param id The node whose predecessors are to marked for execution.
     * @param outPortIndex index of output port this request arrived from
     *   (or -1 if all ports are to be considered)
     *
     * @return true if we found executable predecessor
     */
    private boolean markAndQueueNodeAndPredecessors(final NodeID id, final int outPortIndex) {
        assert !id.equals(this.getID());
        synchronized (m_workflowMutex) {
            NodeContainer nc = getNodeContainer(id);
            // first check some basic facts about this node:
            // 1) executed? - done (and happy)
            if (nc.getInternalState().equals(InternalNodeContainerState.EXECUTED)) {
                // everything fine: found "source" of chain executed
                // Note that we can not assume that an executing metanode
                // is also a good thing: the port this one is connected
                // to may still be idle! So we test for "executing" later
                // for SNC's only (step 3)
                return true;
            }
            // 2) its a to-be-locally-executed WFM:
            if (nc.isLocalWFM()) {
                // hand over control to the sub workflow (who will hand
                // back up to this level if there are (implicit or explicit)
                // through connections to follow:
                WorkflowManager wfm = (WorkflowManager)nc;
                return wfm.markForExecutionAllAffectedNodes(outPortIndex);
            }
            // 3) executing SingleNodeContainer? - done (and happy)
            if (nc.getInternalState().isExecutionInProgress()) {
                // everything fine: found "source" of chain in execution
                return true;
            }
            // 4) now we check if we are dealing with a source (there is no
            //   need to traverse further up then and we can cancel the
            //   operation if the source is in a non-executable condition.
            Set<ConnectionContainer> predConn = m_workflow.getConnectionsByDest(id);
            if (nc.getNrInPorts() == 0) {
                assert predConn.size() == 0;
                if (canExecuteNodeDirectly(nc.getID())) {
                    nc.markForExecution(true);
                    // no need to go through "official" route for SNC as the following queuing will update
                    // state and also state of encapsulating metanode!
                    assert nc.getInternalState().equals(InternalNodeContainerState.CONFIGURED_MARKEDFOREXEC)
                        : "NodeContainer " + nc + " in unexpected state:" + nc.getInternalState();
                    nc.queue(new PortObject[0]);
                    // we are now executing one of the sources of the chain
                    return true;
                } else {
                    // could not find executable predecessor!
                    return false;
                }
            }
            // 5) we fail on nodes which are not fully connected
            //    (whereby unconnected optional inputs are ok)
            NodeOutPort[] predPorts = assemblePredecessorOutPorts(id);
            for (int i = 0; i < predPorts.length; i++) {
                if (predPorts[i] == null && !nc.getInPort(i).getPortType().isOptional()) {
                    return false;
                }
            }
            // 6) now let's see if we can mark the predecessors of this node
            //  (and this way trigger the backwards traversal)
            // handle nodes which are in the middle of a pipeline
            // (A) recurse up to all predecessors of this node (mark/queue them)
            for (ConnectionContainer cc : predConn) {
                NodeID predID = cc.getSource();
                if (predID.equals(getID())) {
                    // connection coming from outside this WFM
                    assert cc.getType().equals(ConnectionContainer.ConnectionType.WFMIN);
                    NodeOutPort realPort = getInPort(cc.getSourcePort()).getUnderlyingPort();
                    if (!realPort.getNodeState().equals(InternalNodeContainerState.EXECUTED)
                            && !realPort.getNodeState().isExecutionInProgress()) {
                        // the real predecessor node is not already marked/done:
                        // we have to mark the predecessor in the parent flow
                        if (!getParent().markAndQueuePredecessors(predID, cc.getSourcePort())) {
                            return false;
                        }
                    }
                } else {
                    if (!markAndQueueNodeAndPredecessors(predID, cc.getSourcePort())) {
                        return false;
                    }
                }
            }
            // (B) check if this node is markable and mark it!
            boolean canBeMarked = true;
            for (NodeOutPort portIt : predPorts) {
                if (portIt != null) {
                    // allowed to be null: could be optional and if not it
                    // was tested above
                    if (!portIt.getNodeState().isExecutionInProgress() && portIt.getPortObject() == null) {
                        // if not executing anymore then we should have
                        // a port object otherwise we can't mark:
                        canBeMarked = false;
                    }
                }
            }
            if (canBeMarked) {
                nc.markForExecution(true);
                queueIfQueuable(nc);
                return true;
            }
        }
        return false;
    }

    /** Queues the argument NC if possible. Does nothing if argument is
     * not marked. Resets marks if node is queuable (all predecessors are done)
     * but its state is still unconfigured (and marked for execution). This
     * will never change again so we can forget about executing also the rest.
     *
     * @param nc To queue if possible
     * @return whether successfully queued.
     */
    private boolean queueIfQueuable(final NodeContainer nc) {
        if (nc.isLocalWFM()) {
            return false;
        }
        if (!isLocalWFM()) {
            switch (getInternalState()) {
            case EXECUTED_MARKEDFOREXEC:
            case CONFIGURED_MARKEDFOREXEC:
            case UNCONFIGURED_MARKEDFOREXEC:
                return getParent().queueIfQueuable(this);
            default:
                return false;
            }
        }
        assert Thread.holdsLock(m_workflowMutex);
        switch (nc.getInternalState()) {
            case UNCONFIGURED_MARKEDFOREXEC:
            case CONFIGURED_MARKEDFOREXEC:
            case EXECUTED_MARKEDFOREXEC:
                break;
            default:
                assert false : "Queuing of " + nc.getNameWithID() + " not possible, node is " + nc.getInternalState();
                return false;
        }
        PortObject[] inData = new PortObject[nc.getNrInPorts()];
        boolean allDataAvailable = assembleInputData(nc.getID(), inData);
        if (allDataAvailable) {
            if (nc.queue(inData)) {
                return true;
            } else {
                // coming from UNCONFIGURED_MARKEDFOREXEC and can't be queued
                // (subnode is special ... it can be queued even if unconfigured_markedforexec)
                disableNodeForExecution(nc.getID());
                // clean loops which were waiting for this one to be executed.
                for (FlowLoopContext flc : nc.getWaitingLoops()) {
                    disableNodeForExecution(flc.getTailNode());
                }
                nc.clearWaitingLoopList();
                checkForNodeStateChanges(true);
                return false;
            }
        }
        return false;
    }

    /* -------------- State changing actions and testers ----------- */

    /**
     * Callback from NodeContainer to request a safe transition into the
     * {@link InternalNodeContainerState#PREEXECUTE} state. This method is mostly
     * only called with {@link SingleNodeContainer} as argument but may also be
     * called with a remotely executed meta node.
     * @param nc node whose execution is about to start
     * @return whether there was an actual state transition, false if the
     *         execution was canceled (cancel checking to be done in
     *         synchronized block)
     */
    boolean doBeforePreExecution(final NodeContainer nc) {
        assert !nc.isLocalWFM() : "No execution of local meta nodes";
        synchronized (m_workflowMutex) {
            LOGGER.debug(nc.getNameWithID() + " doBeforePreExecution");
            if (nc.performStateTransitionPREEXECUTE()) {
                checkForNodeStateChanges(true);
                return true;
            }
            return false;
        }
    }

    /**
     * Callback from NodeContainer to request a safe transition into the
     * {@link InternalNodeContainerState#POSTEXECUTE} state. This method is mostly
     * only called with {@link SingleNodeContainer} as argument but may also be
     * called with a remotely executed meta node.
     * @param nc node whose execution is ending (and is now copying
     *   result data, e.g.)
     * @param status ...
     */
    void doBeforePostExecution(final NodeContainer nc, final NodeContainerExecutionStatus status) {
        assert !nc.isLocalWFM() : "No execution of local meta nodes";
        synchronized (m_workflowMutex) {
            LOGGER.debug(nc.getNameWithID() + " doBeforePostExecution");
            if (nc instanceof NativeNodeContainer && status.isSuccess()) {
                NativeNodeContainer nnc = (NativeNodeContainer)nc;
                if (nnc.getExecutionEnvironment().getUseAsDefault()) {
                    // interactive nodes may have new defaults
                    nnc.saveNodeSettingsToDefault();
                }
            }
            nc.performStateTransitionPOSTEXECUTE();
            checkForNodeStateChanges(true);
        }
    }

    /** Call-back from NodeContainer called before node is actually executed.
     * The argument node is in usually a {@link SingleNodeContainer}, although
     * it can also be a meta node (i.e. a <code>WorkflowManager</code>), which
     * is executed remotely (execution takes place as a single operation).
     *
     * @param nc node whose execution is about to start
     * @throws IllegalFlowObjectStackException If loop end nodes have
     * problems identifying their start node
     */
    void doBeforeExecution(final NodeContainer nc) {
        assert !nc.getID().equals(this.getID());
        assert !nc.isLocalWFM() : "No execution of local meta nodes";
        synchronized (m_workflowMutex) {
            LOGGER.debug(nc.getNameWithID() + " doBeforeExecution");
            // allow NNC to update states etc
            if (nc instanceof NativeNodeContainer) {
                NativeNodeContainer nnc = (NativeNodeContainer)nc;
                FlowObjectStack flowObjectStack = nnc.getFlowObjectStack();
                FlowLoopContext slc = flowObjectStack.peek(FlowLoopContext.class);
                if (slc instanceof RestoredFlowLoopContext) {
                    throw new IllegalFlowObjectStackException(
                            "Can't continue loop as the workflow was "
                            + "restored with the loop being partially "
                            + "executed. Reset loop start and execute "
                            + "entire loop again.");
                }
                if (nnc.isModelCompatibleTo(LoopEndNode.class)) {
                    // if this is an END to a loop, make sure it knows its head
                    if (slc == null) {
                        LOGGER.debug("Incoming flow object stack for " + nnc.getNameWithID() + ":\n"
                                + flowObjectStack.toDeepString());
                        throw new IllegalFlowObjectStackException("Encountered loop-end without corresponding head!");
                    }
                    NodeContainer headNode = m_workflow.getNode(slc.getOwner());
                    if (headNode == null) {
                        throw new IllegalFlowObjectStackException("Loop start and end nodes are not in the"
                                  + " same workflow");
                    }
                    assert ((NativeNodeContainer)headNode).getNode()
                              .getNodeModel().equals(nnc.getNode().getLoopStartNode());
                } else if (nnc.isModelCompatibleTo(LoopStartNode.class)) {
                    nnc.getNode().getOutgoingFlowObjectStack().push(new InnerFlowLoopContext());
//                    nnc.getNode().getFlowObjectStack().push(new InnerFlowLoopContext());
                } else {
                    // or not if it's any other type of node
                    nnc.getNode().setLoopStartNode(null);
                }
            }
            nc.performStateTransitionEXECUTING();
            checkForNodeStateChanges(true);
        }
    }

    /** Cleanup a node after execution. This will also permit the argument node
     * to change its state in {@link NodeContainer#
     * performStateTransitionEXECUTED(NodeContainerExecutionStatus)}.
     * This method also takes care of restarting loops, if there are any to be
     * continued.
     *
     * <p>As in {@link #doBeforeExecution(NodeContainer)} the argument node is
     * usually a {@link SingleNodeContainer} but can also be a remotely executed
     * <code>WorkflowManager</code>.
     *
     * @param nc node which just finished execution
     * @param status indicates if node execution was finished successfully
     *    (note that this does not imply State=EXECUTED e.g. for loop ends)
     */
    void doAfterExecution(final NodeContainer nc, final NodeContainerExecutionStatus status) {
        assert isLocalWFM() : "doAfterExecute not allowed for remotely executing workflows";
        assert !nc.getID().equals(this.getID());
        boolean success = status.isSuccess();
        synchronized (m_workflowMutex) {
            String st = success ? " - success" : " - failure";
            LOGGER.debug(nc.getNameWithID() + " doAfterExecute" + st);
            if (!success) {
                disableNodeForExecution(nc.getID());
            }
            // switch state from POSTEXECUTE to new state: EXECUTED/CONFIGURED
            // in case of success (w/ or w/out loop) or IDLE in case of error.
            nc.performStateTransitionEXECUTED(status);
            boolean canConfigureSuccessors = true;
            if (nc instanceof NativeNodeContainer) {
                NativeNodeContainer nnc = (NativeNodeContainer)nc;
                // remember previous message in case loop restart fails...
                NodeMessage latestNodeMessage = nnc.getNodeMessage();
                if (success) {
                    Node node = nnc.getNode();
                    // process start of bundle of parallel chunks
                    if (node.getNodeModel() instanceof LoopStartParallelizeNode && !node.isInactive()) {
                        try {
                            parallelizeLoop(nc.getID());
                        } catch (Exception e) {
                            if (!(e instanceof IllegalLoopException)) {
                                // handle unexpected exceptions properly (i.e.
                                // clean up loop) but report them as error!
                                LOGGER.error("Error in parallelizeLoop: " + e, e);
                            } else {
                                // can happen during regular use
                                // (e.g. wrong end node)
                                LOGGER.debug("parallelizeLoop failed: " + e, e);
                            }
                            // make sure the start node is reset and
                            // and approriate message is set.
                            latestNodeMessage = new NodeMessage(NodeMessage.Type.ERROR,
                                    "Parallel Branch Start Failure: " + e.getMessage());
                            success = false;
                            canConfigureSuccessors = false;
                            disableNodeForExecution(nc.getID());
                            resetAndConfigureNode(nc.getID());
                        }
                    }
                    // process loop context for "real" nodes:
                    if (nnc.isModelCompatibleTo(LoopStartNode.class)) {
                        // if this was BEGIN, it's not anymore (until we do not
                        // restart it explicitly!)
                        node.setLoopEndNode(null);
                    }
                    if (node.getLoopContext() != null) {
                        assert nnc.isModelCompatibleTo(LoopEndNode.class);
                        // we are supposed to execute this loop again!
                        // first retrieve FlowLoopContext object
                        FlowLoopContext slc = node.getLoopContext();
                        // first check if the loop is properly configured:
                        if (m_workflow.getNode(slc.getHeadNode()) == null) {
                            // obviously not: origin of loop is not in this WFM!
                            // nothing else to do: NC stays configured
                            assert nc.getInternalState() == InternalNodeContainerState.CONFIGURED;
                            // and choke
                            latestNodeMessage = new NodeMessage(NodeMessage.Type.ERROR,
                                    "Loop nodes are not in the same workflow!");
                            success = false;
                        } else {
                            try {
                                // make sure the end of the loop is properly
                                // configured:
                                slc.setTailNode(nc.getID());
                                // and try to restart loop
                                if (!nnc.getNode().getPauseLoopExecution()) {
                                    restartLoop(slc);
                                } else {
                                    // do nothing - leave successors marked...
//                                    disableNodeForExecution(snc.getID());
                                    // and leave flag for now (will be reset
                                    // when execution is resumed).
//                                   snc.getNode().setPauseLoopExecution(false);
                                }
                            } catch (IllegalLoopException ile) {
                                LOGGER.error(ile.getMessage(), ile);
                                latestNodeMessage = new NodeMessage(NodeMessage.Type.ERROR, ile.getMessage());
                                success = false;
                            }
                            // make sure we do not accidentally configure the
                            // remainder of this node since we are not yet done
                            // with the loop
                            canConfigureSuccessors = false;
                        }
                        if (!success) {
                            // make sure any marks are removed
                            // (only for loop ends!)
                            disableNodeForExecution(nnc.getID());
                            nnc.getNode().clearLoopContext();
                        }
                    }
                }
                // note this is NOT the else of the if above - success can
                // be modified...
                if (!success) {
                    // clean up node interna and status (but keep org. message!)
                    // switch from IDLE to CONFIGURED if possible!
                    configureSingleNodeContainer(nnc, /*keepNodeMessage=*/false);
                    nnc.setNodeMessage(latestNodeMessage);
                }
            }
            // now handle non success for all types of nodes:
            if (!success) {
                // clean loops which were waiting for this one to be executed.
                for (FlowLoopContext flc : nc.getWaitingLoops()) {
                    disableNodeForExecution(flc.getTailNode());
                }
                nc.clearWaitingLoopList();
            }
            if (nc.getWaitingLoops().size() >= 1) {
                // looks as if some loops were waiting for this node to
                // finish! Let's try to restart them:
                for (FlowLoopContext slc : nc.getWaitingLoops()) {
                    try {
                        restartLoop(slc);
                    } catch (IllegalLoopException ile) {
                        // set error message in LoopEnd node not this one!
                        NodeMessage nm = new NodeMessage(NodeMessage.Type.ERROR, ile.getMessage());
                        getNodeContainer(slc.getTailNode()).setNodeMessage(nm);
                    }
                }
                nc.clearWaitingLoopList();
            }
            if (canConfigureSuccessors) {
                // may be SingleNodeContainer or WFM contained within this
                // one but then it can be treated like a SNC
                configureNodeAndSuccessors(nc.getID(), false);
            }
            checkForNodeStateChanges(true);
        }
    }

    /** Restart execution of a loop if possible. Can delay restart if
     * we are still waiting for some node in the loop body (or any
     * dangling loop branches) to finish execution
     *
     * @param slc FlowLoopContext of the actual loop
     */
    private void restartLoop(final FlowLoopContext slc)
    throws IllegalLoopException {
        NodeContainer tailNode = m_workflow.getNode(slc.getTailNode());
        NodeContainer headNode = m_workflow.getNode(slc.getOwner());
        if ((tailNode == null) || (headNode == null)) {
            throw new IllegalLoopException("Loop Nodes must both be in the same workflow!");
        }
        if (!(tailNode instanceof NativeNodeContainer) || !(headNode instanceof NativeNodeContainer)) {
            throw new IllegalLoopException("Loop Nodes must both be NativeNodeContainers!");
        }
        // (1) find all intermediate node, the loop's "body"
        ArrayList<NodeAndInports> loopBodyNodes = m_workflow.findAllNodesConnectedToLoopBody(
                                            headNode.getID(), tailNode.getID());
        // (2) check if any of those nodes are still waiting to be
        //     executed or currently executing
        for (NodeAndInports nai : loopBodyNodes) {
            NodeID id = nai.getID();
            NodeContainer currNode = m_workflow.getNode(id);
            if (currNode.getInternalState().isExecutionInProgress()) {
                // stop right here - loop can not yet be restarted!
                currNode.addWaitingLoop(slc);
                return;
            }
            if (this.canExecuteNodeDirectly(id)) {
                // we missed some nodes during the initial marking - because
                // these are part of untouched branches which
                // were not selected to be executed initially.
                // Mark them now and make sure they are already executed
                // during the first iteration (later executions will
                // then execute them automatically).
                // FIXME: (Bug 2292)
                //  - when only 1 iteration is run, this node never executes
                if (currNode instanceof WorkflowManager) {
                    // FIXME: also here we need to execute...?
                } else {
                    assert currNode instanceof SingleNodeContainer;
                    this.markAndQueueNodeAndPredecessors(id, -1);
                    currNode.addWaitingLoop(slc);
                    return;
                }
            }
        }
        // (3) mark the origin of the loop to be executed again
        //     do this now so that we have an executing node in this WFM
        //     and an intermediate state does not suggest everything is done.
        //     (this used to happen before (9))
        // NOTE: if we ever queue nodes asynchronosly this might cause problems.
        NativeNodeContainer headNNC = ((NativeNodeContainer)headNode);
        assert headNNC.isModelCompatibleTo(LoopStartNode.class);
        headNNC.markForReExecution(new ExecutionEnvironment(true, null, false));
        // clean up all newly added objects on FlowVariable Stack
        // (otherwise we will push the same variables many times...
        // push ISLC back onto the stack is done in doBeforeExecute()!
        final FlowObjectStack headOutgoingStack = headNNC.getOutgoingFlowObjectStack();
        headOutgoingStack.pop(InnerFlowLoopContext.class);
        FlowLoopContext flc = headOutgoingStack.peek(FlowLoopContext.class);
        assert !flc.isInactiveScope();
        flc.incrementIterationIndex();
        // (4-7) reset/configure loop body - or not...
        if (headNNC.resetAndConfigureLoopBody()) {
            // (4a) reset the nodes in the body (only those -
            //     make sure end of loop is NOT reset). Make sure reset()
            //     is performed in the correct order (last nodes first!)
            ListIterator<NodeAndInports> li = loopBodyNodes.listIterator(loopBodyNodes.size());
            while (li.hasPrevious()) {
                NodeAndInports nai = li.previous();
                NodeID id = nai.getID();
                NodeContainer nc = m_workflow.getNode(id);
                if (nc == null) {
                    throw new IllegalLoopException("Node in loop body not in same workflow as head&tail!");
                } else if (!nc.isResetable()) {
                    // do not warn - this can actually happen if we (try to) enter a metanode with two inports twice.
                    continue;
                }
                if (nc instanceof SingleNodeContainer) {
                    ((SingleNodeContainer)nc).reset();
                } else {
                    assert nc instanceof WorkflowManager;
                    // only reset the nodes connected to relevant ports.
                    // See also bug 2225
                    ((WorkflowManager)nc).resetNodesInWFMConnectedToInPorts(nai.getInports());
                }
            }
            // clean outports of start but do not call reset
            headNNC.cleanOutPorts(true);
            // (5a) configure the nodes from start to rest (it's not
            //     so important if we configure more than the body)
            //     do NOT configure start of loop because otherwise
            //     we will re-create the FlowObjectStack and
            //     remove the loop-object as well!
            configureNodeAndSuccessors(headNode.getID(), false);
            // the tail node may have thrown an exception inside
            // configure, so we have to check here if the node
            // is really configured before. (Failing configures in
            // loop body nodes do NOT affect the state of the tailNode.)
            if (tailNode.getInternalState().equals(InternalNodeContainerState.CONFIGURED_MARKEDFOREXEC)) {
                // (6a) ... we enable the body to be queued again.
                for (NodeAndInports nai : loopBodyNodes) {
                    NodeID id = nai.getID();
                    NodeContainer nc = m_workflow.getNode(id);
                    if (nc instanceof SingleNodeContainer) {
                        // make sure it's not already done...
                        if (nc.getInternalState().equals(InternalNodeContainerState.IDLE)
                                || nc.getInternalState().equals(InternalNodeContainerState.CONFIGURED)) {
                            ((SingleNodeContainer)nc).markForExecution(true);
                        }
                    } else {
                        // Mark only idle or configured nodes for re-execution
                        // which are part of the flow.
                        ((WorkflowManager)nc).markForExecutionNodesInWFMConnectedToInPorts(nai.getInports(), false);
                    }
                }
//                // and (7a) mark end of loop for re-execution
                // not needed anymore: end-of-loop state _is_ MARKEDFOREXEC!
//                ((SingleNodeContainer)tailNode).markForExecution(true);
            } else {
                // configure of tailNode failed! Abort execution of loop:
                // unqueue head node
                headNNC.markForExecution(false);
                // and bail:
                throw new IllegalLoopException("Loop end node could not be executed."
                           + " This is likely due to a failure in the loop's body. Aborting Loop execution.");
            }
        } else {
            // (4b-5b) skip reset/configure... just clean outports
            ListIterator<NodeAndInports> li = loopBodyNodes.listIterator(loopBodyNodes.size());
            while (li.hasPrevious()) {
                NodeAndInports nai = li.previous();
                NodeID id = nai.getID();
                NodeContainer nc = m_workflow.getNode(id);
                if (nc == null) {
                    throw new IllegalLoopException("Node in loop body not in same workflow as head&tail!");
                }
                if (nc instanceof SingleNodeContainer) {
                    ((SingleNodeContainer)nc).cleanOutPorts(true);
                } else {
                    WorkflowManager wm = (WorkflowManager)nc;
                    wm.cleanOutputPortsInWFMConnectedToInPorts(nai.getInports());
                }
            }
            // clean outports of start but do not call reset
            headNNC.cleanOutPorts(true);
            // (6b) ...only re-"mark" loop body (tail is already marked)
            for (NodeAndInports nai : loopBodyNodes) {
                NodeID id = nai.getID();
                NodeContainer nc = m_workflow.getNode(id);
                if (nc instanceof SingleNodeContainer) {
                    // make sure it's not already done...
                    if (nc.getInternalState().equals(InternalNodeContainerState.EXECUTED)) {
                        ((SingleNodeContainer)nc).markForReExecution(new ExecutionEnvironment(false, null, false));
                    }
                } else {
                    // Mark executed nodes for re-execution (will also mark
                    // queuded and idle nodes but those don't exist)
                    ((WorkflowManager)nc).markForExecutionNodesInWFMConnectedToInPorts(nai.getInports(), true);
                }
            }
            // and (7b) mark end of loop for re-execution
//            assert tailNode.getState().equals(State.CONFIGURED);
//            ((SingleNodeContainer)tailNode).markForExecution(true);
            // see above - state is ok
            assert tailNode.getInternalState().equals(InternalNodeContainerState.CONFIGURED_MARKEDFOREXEC);
        }
        // (8) allow access to tail node
        ((NativeNodeContainer)headNode).getNode().setLoopEndNode(((NativeNodeContainer)tailNode).getNode());
        // (9) and finally try to queue the head of this loop!
        assert headNode.getInternalState().equals(InternalNodeContainerState.EXECUTED_MARKEDFOREXEC);
        queueIfQueuable(headNode);
    }

    /* Parallelize this "loop": create appropriate number of parallel
     * branches executing the matching chunks.
     */
    private void parallelizeLoop(final NodeID startID)
    throws IllegalLoopException {
        synchronized (m_workflowMutex) {
            final NodeID endID = m_workflow.getMatchingLoopEnd(startID);
            LoopEndParallelizeNode endNode;
            LoopStartParallelizeNode startNode;
            try {
                // just for validation
                startNode = castNodeModel(startID, LoopStartParallelizeNode.class);
                endNode = castNodeModel(endID, LoopEndParallelizeNode.class);
            } catch (IllegalArgumentException iae) {
                throw new IllegalLoopException("Parallel Chunk Start Node not connected to matching end node!", iae);
            }

            final ArrayList<NodeAndInports> loopBody =
                   m_workflow.findAllNodesConnectedToLoopBody(startID, endID);
            NodeID[] loopNodes = new NodeID[loopBody.size()];
            loopNodes[0] = startID;
            for (int i = 0; i < loopBody.size(); i++) {
                loopNodes[i] = loopBody.get(i).getID();
            }
            // creating matching sub workflow node holding all chunks
            Set<Pair<NodeID, Integer>> exposedInports =
                           findNodesWithExternalSources(startID, loopNodes);
            HashMap<Pair<NodeID, Integer>, Integer> extInConnections
                            = new HashMap<Pair<NodeID, Integer>, Integer>();
            PortType[] exposedInportTypes =
                                     new PortType[exposedInports.size() + 1];
            // the first port is the variable port
            exposedInportTypes[0] = FlowVariablePortObject.TYPE;
            // the remaining ports cover the exposed inports of the loop body
            int index = 1;
            for (Pair<NodeID, Integer> npi : exposedInports) {
                NodeContainer nc = getNodeContainer(npi.getFirst());
                int portIndex = npi.getSecond();
                exposedInportTypes[index] =
                                       nc.getInPort(portIndex).getPortType();
                extInConnections.put(npi, index);
                index++;
            }
            WorkflowManager subwfm = createAndAddSubWorkflow(
                    exposedInportTypes, new PortType[0], "Parallel Chunks");
            NodeUIInformation startUIPlain = getNodeContainer(startID).getUIInformation();
            if (startUIPlain != null) {
                NodeUIInformation startUI = startUIPlain.
                    createNewWithOffsetPosition(new int[]{60, -60, 0, 0});
                subwfm.setUIInformation(startUI);
            }
            // connect outside(!) nodes to new sub metanode
            for (Map.Entry<Pair<NodeID, Integer>, Integer> entry : extInConnections.entrySet()) {
                final Pair<NodeID, Integer> npi = entry.getKey();
                int metanodeindex = entry.getValue();
                if (metanodeindex >= 0) {  // ignore variable port!
                    // we need to find the source again (since our list
                    // only holds the destination...)
                    ConnectionContainer cc = this.getIncomingConnectionFor(
                            npi.getFirst(), npi.getSecond());
                    this.addConnection(cc.getSource(), cc.getSourcePort(),
                            subwfm.getID(), metanodeindex);
                }
            }
            ParallelizedChunkContentMaster pccm = new ParallelizedChunkContentMaster(subwfm, endNode,
                                                 startNode.getNrRemoteChunks());
            for (int i = 0; i < startNode.getNrRemoteChunks(); i++) {
                ParallelizedChunkContent copiedNodes = duplicateLoopBodyInSubWFMandAttach(
                          subwfm, extInConnections, startID, endID, loopNodes, i);
                copiedNodes.executeChunk();
                pccm.addParallelChunk(i, copiedNodes);
            }
            // make sure head knows his chunk master (for potential cleanup)
            startNode.setChunkMaster(pccm);
        }
    }

    /*
     * Identify all nodes that have incoming connections which are not part
     * of a given set of nodes.
     *
     * @param startID id of first node (don't include)
     * @param ids NodeIDs of set of nodes
     * @return set of NodeIDs and inport indices that have outside conn.
     */
    private Set<Pair<NodeID, Integer>> findNodesWithExternalSources(
            final NodeID startID,
            final NodeID[] ids) {
        // for quick search:
        HashSet<NodeID> orgIDsHash = new HashSet<NodeID>(Arrays.asList(ids));
        // result
        HashSet<Pair<NodeID, Integer>> exposedInports =
            new HashSet<Pair<NodeID, Integer>>();
        for (NodeID id : ids) {
            if (m_workflow.getConnectionsByDest(id) != null) {
                for (ConnectionContainer cc : m_workflow.getConnectionsByDest(id)) {
                    if ((!orgIDsHash.contains(cc.getSource()))
                        && (!cc.getSource().equals(startID))) {
                        Pair<NodeID, Integer> npi
                                = new Pair<NodeID, Integer>(cc.getDest(),
                                                            cc.getDestPort());
                        if (!exposedInports.contains(npi)) {
                            exposedInports.add(npi);
                        }
                    }
                }
            }
        }
        return exposedInports;
    }

    /*
     * ...
     * @param subWFM already prepared subworkflow with appropriate
     *   inports. If subWFM==this then the subworkflows are simply
     *   added to the same workflow.
     * @param extInConnections map of incoming connections
     *   (NodeID + PortIndex) => WFM-Inport. Can be null if subWFM==this.
     * ...
     */
    private ParallelizedChunkContent duplicateLoopBodyInSubWFMandAttach(
            final WorkflowManager subWFM,
            final HashMap<Pair<NodeID, Integer>, Integer> extInConnections,
            final NodeID startID, final NodeID endID, final NodeID[] oldIDs,
            final int chunkIndex) {
        assert Thread.holdsLock(m_workflowMutex);
        // compute offset for new nodes (shifted in case of same
        // workflow, otherwise just underneath each other)
        final int[] moveUIDist;
        if (subWFM == this) {
            moveUIDist = new int[]{(chunkIndex + 1) * 10,
                    (chunkIndex + 1) * 80, 0, 0};
        } else {
            moveUIDist = new int[]{(chunkIndex + 1) * 0,
                    (chunkIndex + 1) * 150, 0, 0};
        }
        // create virtual start node
        NodeContainer startNode = getNodeContainer(startID);
        // find port types (ignore Variable Port "ear")
        PortType[] outTypes = new PortType[startNode.getNrOutPorts() - 1];
        for (int i = 0; i < outTypes.length; i++) {
            outTypes[i] = startNode.getOutPort(i + 1).getPortType();
        }
        NodeID virtualStartID = subWFM.createAndAddNode(
                new VirtualParallelizedChunkPortObjectInNodeFactory(outTypes));
        NodeUIInformation startUIPlain = startNode.getUIInformation();
        if (startUIPlain != null) {
            NodeUIInformation startUI = startUIPlain.
                createNewWithOffsetPosition(moveUIDist);
            subWFM.getNodeContainer(virtualStartID).setUIInformation(startUI);
        }
        // create virtual end node
        NodeContainer endNode = getNodeContainer(endID);
        assert endNode instanceof SingleNodeContainer;
        // find port types (ignore Variable Port "ear")
        PortType[] realInTypes = new PortType[endNode.getNrInPorts() - 1];
        for (int i = 0; i < realInTypes.length; i++) {
            realInTypes[i] = endNode.getInPort(i + 1).getPortType();
        }
        NodeID virtualEndID = subWFM.createAndAddNode(
                new VirtualParallelizedChunkPortObjectOutNodeFactory(realInTypes));
        NodeUIInformation endUIPlain = endNode.getUIInformation();
        if (endUIPlain != null) {
            NodeUIInformation endUI = endUIPlain.
                createNewWithOffsetPosition(moveUIDist);
            subWFM.getNodeContainer(virtualEndID).setUIInformation(endUI);
        }
        // copy nodes in loop body
        WorkflowCopyContent copyContent = new WorkflowCopyContent();
        copyContent.setNodeIDs(oldIDs);
        WorkflowCopyContent newBody
            = subWFM.copyFromAndPasteHere(this, copyContent);
        NodeID[] newIDs = newBody.getNodeIDs();
        Map<NodeID, NodeID> oldIDsHash = new HashMap<NodeID, NodeID>();
        for (int i = 0; i < oldIDs.length; i++) {
            oldIDsHash.put(oldIDs[i], newIDs[i]);
            NodeContainer nc = subWFM.getNodeContainer(newIDs[i]);
            NodeUIInformation uiInfo = nc.getUIInformation();
            if (uiInfo != null) {
                nc.setUIInformation(uiInfo.createNewWithOffsetPosition(moveUIDist));
            }
        }
        // restore connections to nodes outside the loop body (only incoming)
        for (int i = 0; i < newIDs.length; i++) {
            NodeContainer oldNode = getNodeContainer(oldIDs[i]);
            for (int p = 0; p < oldNode.getNrInPorts(); p++) {
                ConnectionContainer c = getIncomingConnectionFor(oldIDs[i], p);
                if (c == null) {
                    // ignore: no incoming connection
                } else if (oldIDsHash.containsKey(c.getSource())) {
                    // ignore: connection already retained by paste persistor
                } else if (c.getSource().equals(startID)) {
                    // used to connect to start node, connect to virtual in now
                    subWFM.addConnection(virtualStartID, c.getSourcePort(),
                            newIDs[i], c.getDestPort());
                } else {
                    // source node not part of loop:
                    if (subWFM == this) {
                        addConnection(c.getSource(), c.getSourcePort(),
                                newIDs[i], c.getDestPort());
                    } else {
                        // find new replacement port
                        int subWFMportIndex = extInConnections.get(
                                new Pair<NodeID, Integer>(c.getDest(),
                                                          c.getDestPort()));
                        subWFM.addConnection(subWFM.getID(), subWFMportIndex,
                                newIDs[i], c.getDestPort());
                    }
                }
            }
        }
        // attach incoming connections of new Virtual End Node
        for (int p = 0; p < endNode.getNrInPorts(); p++) {
            ConnectionContainer c = getIncomingConnectionFor(endID, p);
            if (c == null) {
                // ignore: no incoming connection
            } else if (oldIDsHash.containsKey(c.getSource())) {
                // connects to node in loop - connect to copy
                NodeID source = oldIDsHash.get(c.getSource());
                subWFM.addConnection(source, c.getSourcePort(),
                        virtualEndID, c.getDestPort());
            } else if (c.getSource().equals(startID)) {
                // used to connect to start node, connect to virtual in now
                subWFM.addConnection(virtualStartID, c.getSourcePort(),
                        virtualEndID, c.getDestPort());
            } else {
                // source node not part of loop
                if (subWFM == this) {
                    addConnection(c.getSource(), c.getSourcePort(),
                            virtualEndID, c.getDestPort());
                } else {
                    // find new replacement port
                    int subWFMportIndex = extInConnections.get(
                            new Pair<NodeID, Integer>(c.getSource(),
                                                      c.getSourcePort()));
                    subWFM.addConnection(this.getID(), subWFMportIndex,
                            virtualEndID, c.getDestPort());
                }
            }
        }
        if (subWFM == this) {
            // connect start node var port with virtual start node
            addConnection(startID, 0, virtualStartID, 0);
        } else {
            // add variable connection to port 0 of WFM!
            if (this.canAddConnection(startID, 0, subWFM.getID(), 0)) {
                // only add this one the first time...
                this.addConnection(startID, 0, subWFM.getID(), 0);
            }
            subWFM.addConnection(subWFM.getID(), 0, virtualStartID, 0);
        }
        // set chunk of table to be processed in new virtual start node
        LoopStartParallelizeNode startModel =
            castNodeModel(startID, LoopStartParallelizeNode.class);
        VirtualParallelizedChunkNodeInput data = startModel.getVirtualNodeInput(chunkIndex);
        VirtualParallelizedChunkPortObjectInNodeModel virtualInModel =
            subWFM.castNodeModel(virtualStartID, VirtualParallelizedChunkPortObjectInNodeModel.class);
        virtualInModel.setVirtualNodeInput(data);
        return new ParallelizedChunkContent(subWFM, virtualStartID,
                virtualEndID, newIDs);
    }

    /** Check if we can expand the selected metanode into a set of nodes in
     * this WFM.
     * This essentially checks if the nodes can be moved (=deleted from
     * the original WFM) or if they are executed
     *
     * @param subNodeID the id of the metanode to be expanded
     * @return null of ok otherwise reason (String) why not
     * @since 2.10
     */
    public String canExpandSubNode(final NodeID subNodeID) {
        synchronized (m_workflowMutex) {
            if (!(getNodeContainer(subNodeID) instanceof SubNodeContainer)) {
                return "Cannot expand selected node (not a subnode).";
            }
            if (!canRemoveNode(subNodeID)) {
                return "Cannot move subnode or nodes inside subnode (node(s) or successor still executing?)";
            }
            WorkflowManager wfm = ((SubNodeContainer)getNodeContainer(subNodeID)).getWorkflowManager();
            if (wfm.containsExecutedNode()) {
                return "Cannot expand executed sub node (reset first).";
            }
            return null;
        }
    }

    /** Check if we can expand the selected metanode into a set of nodes in
     * this WFM.
     * This essentially checks if the nodes can be moved (=deleted from
     * the original WFM) or if they are executed
     *
     * @param wfmID the id of the metanode to be expanded
     * @return null of ok otherwise reason (String) why not
     */
    public String canExpandMetaNode(final NodeID wfmID) {
        synchronized (m_workflowMutex) {
            NodeContainer nc = m_workflow.getNode(wfmID);
            if (nc == null) {
                return "No node with id \"" + wfmID + "\"";
            }
            if (!(nc instanceof WorkflowManager)) {
                return "Cannot expand selected node (not a metanode).";
            }
            if (!canRemoveNode(wfmID)) {
                return "Cannot move metanode or nodes inside metanode (node(s) or successor still executing?)";
            }
            WorkflowManager wfm = (WorkflowManager)(getNodeContainer(wfmID));
            if (wfm.containsExecutedNode()) {
                return "Cannot expand executed meta node (reset first).";
            }
            return null;
        }
    }

    /** Expand the selected metanode into a set of nodes in
     * this WFM and remove the old metanode.
     *
     * @param wfmID the id of the metanode to be expanded
     * @return copied content containing nodes and annotations
     * @throws IllegalArgumentException if expand can not be done
     */
    public WorkflowCopyContent expandMetaNode(final NodeID wfmID) throws IllegalArgumentException {
        return expandSubWorkflow(wfmID, (WorkflowManager)getNodeContainer(wfmID));
    }

    /** Expand the selected metanode into a set of nodes in
     * this WFM and remove the old metanode.
     *
     * @param nodeID ID of the node containing the sub workflow
     * @param subWFM The sub workflow
     * @return copied content containing nodes and annotations
     * @throws IllegalArgumentException if expand can not be done
     * @since 2.10
     */
    public WorkflowCopyContent expandSubWorkflow(final NodeID nodeID, final WorkflowManager subWFM)
            throws IllegalArgumentException {
        synchronized (m_workflowMutex) {
            NodeContainer node = getNodeContainer(nodeID);
            HashSet<NodeID> virtualNodes = new HashSet<NodeID>();
            if (node instanceof WorkflowManager) {
                // check again, to be sure...
                String res = canExpandMetaNode(nodeID);
                if (res != null) {
                    throw new IllegalArgumentException(res);
                }
            } else if (node instanceof SubNodeContainer) {
                // check again, to be sure...
                String res = canExpandSubNode(nodeID);
                if (res != null) {
                    throw new IllegalArgumentException(res);
                }
                SubNodeContainer snc = (SubNodeContainer)node;
                virtualNodes.add(snc.getVirtualInNodeID());
                virtualNodes.add(snc.getVirtualOutNodeID());
            }
            // retrieve all nodes from metanode
            Collection<NodeContainer> ncs = subWFM.getNodeContainers();
            NodeID[] orgIDs = new NodeID[ncs.size()];
            int i = 0;
            for (NodeContainer nc : ncs) {
                orgIDs[i] = nc.getID();
                i++;
            }
            // retrieve all workflow annotations
            Collection<WorkflowAnnotation> annos =
                subWFM.getWorkflowAnnotations();
            WorkflowAnnotation[] orgAnnos = annos.toArray(
                    new WorkflowAnnotation[annos.size()]);
            // copy the nodes from the sub workflow manager:
            WorkflowCopyContent orgContent = new WorkflowCopyContent();
            orgContent.setNodeIDs(orgIDs);
            orgContent.setAnnotation(orgAnnos);
            WorkflowCopyContent newContent = this.copyFromAndPasteHere(subWFM, orgContent);
            NodeID[] newIDs = newContent.getNodeIDs();
            Annotation[] newAnnos = newContent.getAnnotations();
            // create map and set of quick lookup/search
            Map<NodeID, NodeID> oldIDsHash = new HashMap<NodeID, NodeID>();
            HashSet<NodeID> newIDsHashSet = new HashSet<NodeID>();
            for (i = 0; i < orgIDs.length; i++) {
                oldIDsHash.put(orgIDs[i], newIDs[i]);
                newIDsHashSet.add(newIDs[i]);
            }
            if (node instanceof WorkflowManager) {
                // connect connections TO the sub workflow:
                for (ConnectionContainer cc : m_workflow.getConnectionsByDest(subWFM.getID())) {
                    int destPortIndex = cc.getDestPort();
                    for (ConnectionContainer subCC : subWFM.m_workflow.getConnectionsBySource(subWFM.getID())) {
                        if (subCC.getSourcePort() == destPortIndex) {
                            if (subCC.getDest().equals(subWFM.getID())) {
                                // THROUGH connection - skip here, handled below!
                            } else {
                                // reconnect
                                NodeID newID = oldIDsHash.get(subCC.getDest());
                                this.addConnection(cc.getSource(), cc.getSourcePort(), newID, subCC.getDestPort());
                            }
                        }
                    }
                }
                // connect connection FROM the sub workflow
                for (ConnectionContainer cc : getOutgoingConnectionsFor(subWFM.getID())) {
                    int sourcePortIndex = cc.getSourcePort();
                    ConnectionContainer subCC = subWFM.getIncomingConnectionFor(subWFM.getID(), sourcePortIndex);
                    if (subCC != null) {
                        if (subCC.getSource().equals(subWFM.getID())) {
                            // THROUGH connection
                            ConnectionContainer incomingCC
                                            = this.getIncomingConnectionFor(subWFM.getID(), subCC.getSourcePort());
                            // delete existing connection from Metanode to
                            // Node (done automatically) and reconnect
                            this.addConnection(incomingCC.getSource(), incomingCC.getSourcePort(),
                                    cc.getDest(), cc.getDestPort());
                        } else {
                            // delete existing connection from Metanode to Node (automatically) and reconnect
                            NodeID newID = oldIDsHash.get(subCC.getSource());
                            this.addConnection(newID, subCC.getSourcePort(), cc.getDest(), cc.getDestPort());
                        }
                    }
                }
            } else if (node instanceof SubNodeContainer) {
                // connect connections TO the sub workflow:
                for (ConnectionContainer outerConnection : m_workflow.getConnectionsByDest(nodeID)) {
                    for (ConnectionContainer innerConnection : subWFM.m_workflow.getConnectionsBySource(
                        ((SubNodeContainer)node).getVirtualInNodeID())) {
                        if (outerConnection.getDestPort() == innerConnection.getSourcePort()) {
                            addConnection(outerConnection.getSource(), outerConnection.getSourcePort(),
                                oldIDsHash.get(innerConnection.getDest()), innerConnection.getDestPort());
                        }
                    }
                }
                // connect connections FROM the sub workflow:
                List<ConnectionContainer> cons = new ArrayList<ConnectionContainer>();
                cons.addAll(m_workflow.getConnectionsBySource(nodeID));
                for (ConnectionContainer outerConnection : cons) {
                    for (ConnectionContainer innerConnection : subWFM.m_workflow.getConnectionsByDest(
                        ((SubNodeContainer)node).getVirtualOutNodeID())) {
                        if (outerConnection.getSourcePort() == innerConnection.getDestPort()) {
                            addConnection(oldIDsHash.get(innerConnection.getSource()), innerConnection.getSourcePort(),
                                outerConnection.getDest(), outerConnection.getDestPort());
                        }
                    }
                }
            }
            // move nodes so that their center lies on the position of
            // the old metanode!
            // ATTENTION: if you change this make sure it is (correctly)
            // revertable by collapseToMetaNodes (undo-redo!)
            int xmin = Integer.MAX_VALUE;
            int ymin = Integer.MAX_VALUE;
            int xmax = Integer.MIN_VALUE;
            int ymax = Integer.MIN_VALUE;
            for (i = 0; i < newIDs.length; i++) {
                NodeContainer nc = getNodeContainer(newIDs[i]);
                NodeUIInformation uii = nc.getUIInformation();
                if (uii != null) {
                    int[] bounds = uii.getBounds();
                    if (bounds.length >= 2) {
                        xmin = Math.min(bounds[0], xmin);
                        ymin = Math.min(bounds[1], ymin);
                        xmax = Math.max(bounds[0], xmax);
                        ymax = Math.max(bounds[1], ymax);
                    }
                }
            }
            NodeUIInformation uii = node.getUIInformation();
            if (uii != null) {
                int[] metaBounds = uii.getBounds();
                int xShift = metaBounds[0] - (xmin + xmax) / 2;
                int yShift = metaBounds[1] - (ymin + ymax) / 2;
                for (i = 0; i < newIDs.length; i++) {
                    NodeContainer nc = getNodeContainer(newIDs[i]);
                    uii = nc.getUIInformation();
                    if (uii != null) {
                        NodeUIInformation newUii
                           = uii.createNewWithOffsetPosition(
                                   new int[]{xShift, yShift});
                        nc.setUIInformation(newUii);
                    }
                }
                for (Annotation anno : newAnnos) {
                    anno.shiftPosition(xShift, yShift);
                }
                // move bendpoints of connections between moved nodes
                for (ConnectionContainer cc : this.getConnectionContainers()) {
                    if ((newIDsHashSet.contains(cc.getSource()))
                            && (newIDsHashSet.contains(cc.getDest()))) {
                        ConnectionUIInformation cuii = cc.getUIInfo();
                        if (cuii != null) {
                            ConnectionUIInformation newUI =
                                cuii.createNewWithOffsetPosition(
                                        new int[] {xShift, yShift});
                            cc.setUIInfo(newUI);
                        }
                    }

                }
            }
            // remove virtual nodes
            for (NodeID id : virtualNodes) {
                List<ConnectionContainer> connections = new ArrayList<ConnectionContainer>();
                connections.addAll(m_workflow.getConnectionsByDest(oldIDsHash.get(id)));
                for (ConnectionContainer cc : connections) {
                    m_workflow.removeConnection(cc);
                }
                m_workflow.removeNode(oldIDsHash.get(id));
            }
            // and finally remove old sub workflow
            this.removeNode(nodeID);
            return newContent;
        }
    }

    /** Convert the selected metanode into a subnode.
     *
     * @param wfmID the id of the metanode to be converted.
     * @return ID to the created sub node.
     * @since 2.10
     */
    public NodeID convertMetaNodeToSubNode(final NodeID wfmID) {
        synchronized (m_workflowMutex) {
            //
            WorkflowManager subWFM = getNodeContainer(wfmID, WorkflowManager.class, true);
            final Set<ConnectionContainer> connectionsByDestination =
                    new LinkedHashSet<>(m_workflow.getConnectionsByDest(subWFM.getID()));
            final Set<ConnectionContainer> connectionsBySource =
                    new LinkedHashSet<>(m_workflow.getConnectionsBySource(subWFM.getID()));
            NodeUIInformation uii = subWFM.getUIInformation();

            removeNode(wfmID);

            SubNodeContainer subNC = new SubNodeContainer(this, wfmID, subWFM, subWFM.getName());
            this.addNodeContainer(subNC, /*propagateChanges=*/true);

            // rewire connections TO the old metanode:
            for (ConnectionContainer cc : connectionsByDestination) {
                this.addConnection(cc.getSource(), cc.getSourcePort(), subNC.getID(), cc.getDestPort() + 1);
            }

            // rewire connections FROM the sub workflow
            for (ConnectionContainer cc : connectionsBySource) {
                this.addConnection(subNC.getID(), cc.getSourcePort() + 1, cc.getDest(), cc.getDestPort());
            }
            // move SubNode to position of old Metanode (and remove it)
            subNC.setUIInformation(uii);

            configureNodeAndSuccessors(subNC.getID(), /*configureMyself=*/true);
            return subNC.getID();
        }
    }

    /** Check if we can collapse selected set of nodes into a metanode.
     * This essentially checks if the nodes can be moved (=deleted from
     * the original WFM), if they are executed, or if moving them would
     * result in cycles in the original WFM (outgoing connections fed
     * back into inports of the new Metanode).
     *
     * @param orgIDs the ids of the nodes to be moved to the new metanode.
     * @return null or reason why this can not be done as string.
     */
    public String canCollapseNodesIntoMetaNode(final NodeID[] orgIDs) {
        synchronized (m_workflowMutex) {
            // for quick search:
            HashSet<NodeID> orgIDsHash = new HashSet<NodeID>(Arrays.asList(orgIDs));
            // Check if we are allowed to move (=delete) all those nodes
            for (NodeID id : orgIDs) {
                if (!canRemoveNode(id)) {
                    // we can not - bail!
                    return "Cannot move all selected nodes (successor executing?).";
                }
            }
            // Check if any of those nodes are executed
            for (NodeID id : orgIDs) {
                NodeContainer nc = getNodeContainer(id);
                if (InternalNodeContainerState.EXECUTED.equals(nc.getInternalState())) {
                    // we can not - bail!
                    return "Cannot move executed nodes (reset first).";
                }
            }
            // Check if move will create loops in WFM connected to new Metanode
            // a) first find set of nodes connected to the selected ones and not
            //    part of the list
            HashSet<NodeID> ncNodes = new HashSet<NodeID>();
            for (NodeID id : orgIDs) {
                for (ConnectionContainer cc : m_workflow.getConnectionsBySource(id)) {
                    NodeID destID = cc.getDest();
                    if ((!this.getID().equals(destID)) && (!orgIDsHash.contains(destID))) {
                        // successor which is not part of list - remember it!
                        ncNodes.add(destID);
                    }
                }
            }
            // b) check if any successor of those nodes is IN our list!
            while (!ncNodes.isEmpty()) {
                NodeID thisID = ncNodes.iterator().next();
                ncNodes.remove(thisID);
                for (ConnectionContainer cc : m_workflow.getConnectionsBySource(thisID)) {
                    NodeID destID = cc.getDest();
                    if (!this.getID().equals(destID)) {
                        if (orgIDsHash.contains(destID)) {
                            // successor is in our original list - bail!
                            return "Cannot move nodes - selected set is not closed!";
                        }
                        ncNodes.add(destID);
                    }
                }
            }
        }
        return null;
    }

    /* Little helper class to enable vertical sorting of collapsed meta
     * node in- and outports.
     */
    private static class VerticalPortIndex implements Comparable<VerticalPortIndex> {
        private int m_index = -1;
        private final int m_yPos;
        public VerticalPortIndex(final int y) {
            m_yPos = y;
        }
        public int getIndex() { return m_index; }
        public void setIndex(final int i) { m_index = i; }
        /** {@inheritDoc} */
        @Override
        public int compareTo(final VerticalPortIndex arg) {
            return Double.compare(m_yPos, arg.m_yPos);
        }
        /** {@inheritDoc} */
        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof VerticalPortIndex)) {
                return false;
            }
            return m_yPos == ((VerticalPortIndex)o).m_yPos;
        }
        /** {@inheritDoc} */
        @Override
        public int hashCode() {
            return m_index + m_yPos;
        }
    }

    /** Collapse selected set of nodes into a metanode. Make sure connections
     * from and to nodes not contained in this set are passed through
     * appropriate ports of the new metanode.
     *
     * @param orgIDs the ids of the nodes to be moved to the new metanode.
     * @param orgAnnos the workflow annotations to be moved
     * @param name of the new metanode
     * @return newly create metanode
     * @throws IllegalArgumentException if collapse can not be done
     */
    public WorkflowManager collapseIntoMetaNode(final NodeID[] orgIDs,
            final WorkflowAnnotation[] orgAnnos, final String name)
    throws IllegalArgumentException {
        synchronized (m_workflowMutex) {
            // make sure this is still true:
            String res = canCollapseNodesIntoMetaNode(orgIDs);
            if (res != null) {
                throw new IllegalArgumentException(res);
            }
            // for quick search:
            HashSet<NodeID> orgIDsHash = new HashSet<NodeID>(Arrays.asList(orgIDs));
            // find outside Nodes/Ports that have connections to the inside.
            // Map will hold SourceNodeID/PortIndex + Index of new MetanodeInport.
            HashMap<Pair<NodeID, Integer>, VerticalPortIndex> exposedIncomingPorts =
                new HashMap<Pair<NodeID, Integer>, VerticalPortIndex>();
            // second Map holds list of affected connections
            HashMap<ConnectionContainer, VerticalPortIndex> inportConnections =
                         new HashMap<ConnectionContainer, VerticalPortIndex>();
            for (NodeID id : orgIDs) {
                if (m_workflow.getConnectionsByDest(id) != null) {
                    for (ConnectionContainer cc : m_workflow.getConnectionsByDest(id)) {
                        if (!orgIDsHash.contains(cc.getSource())) {
                            Pair<NodeID, Integer> npi
                                    = new Pair<NodeID, Integer>(cc.getSource(),
                                                                cc.getSourcePort());
                            if (!exposedIncomingPorts.containsKey(npi)) {
                                int yPos = npi.getSecond();
                                if (npi.getFirst().equals(this.getID())) {
                                    // connection from metanode inport
                                    // TODO: determine ypos of the port!
                                } else {
                                    // connected to other node in this workflow
                                    NodeContainer nc = getNodeContainer(npi.getFirst());
                                    NodeUIInformation uii = nc.getUIInformation();
                                    // also include source port index into the ypos
                                    // to make sure ports of the same node are sorted
                                    // correctly!
                                    if (uii != null) {
                                        int[] x = uii.getBounds();
                                        if ((x != null) && (x.length >= 2)) {
                                            // add node y position to port index
                                            yPos += x[1];
                                        }
                                    }
                                }
                                VerticalPortIndex vpi = new VerticalPortIndex(yPos);
                                exposedIncomingPorts.put(npi, vpi);
                            }
                            VerticalPortIndex inportIndex = exposedIncomingPorts.get(npi);
                            inportConnections.put(cc, inportIndex);
                        }
                    }
                }
            }
            // sort new input ports by vertical position of source nodes
            VerticalPortIndex[] vpis =
                new VerticalPortIndex[exposedIncomingPorts.size()];
            int vi = 0;
            for (VerticalPortIndex vpi : exposedIncomingPorts.values()) {
                vpis[vi] = vpi;
                vi++;
            }
            Arrays.sort(vpis);
            for (int i = 0; i < vpis.length; i++) {
                vpis[i].setIndex(i);
            }
            // find Nodes/Ports that have outgoing connections to the outside.
            // Map will hold SourceNodeID/PortIndex + Index of new MetanodeOutport.
            HashMap<Pair<NodeID, Integer>, VerticalPortIndex> exposedOutports =
                new HashMap<Pair<NodeID, Integer>, VerticalPortIndex>();
            for (NodeID id : orgIDs) {
                for (ConnectionContainer cc : m_workflow.getConnectionsBySource(id)) {
                    if (!orgIDsHash.contains(cc.getDest())) {
                        Pair<NodeID, Integer> npi
                                = new Pair<NodeID, Integer>(cc.getSource(),
                                                            cc.getSourcePort());
                        if (!exposedOutports.containsKey(npi)) {
                            NodeContainer nc = getNodeContainer(npi.getFirst());
                            NodeUIInformation uii = nc.getUIInformation();
                            // also include source port index into the ypos
                            // to make sure ports of the same node are sorted
                            // correctly!
                            int yPos = npi.getSecond();
                            if (uii != null) {
                                int[] x = uii.getBounds();
                                if ((x != null) && (x.length >= 2)) {
                                    // add node y position to port index
                                    yPos += x[1];
                                }
                            }
                            VerticalPortIndex vpi = new VerticalPortIndex(yPos);
                            exposedOutports.put(npi, vpi);
                        }
                    }
                }
            }
            // also sort new output ports by vertical position of source nodes
            vpis = new VerticalPortIndex[exposedOutports.size()];
            vi = 0;
            for (VerticalPortIndex vpi : exposedOutports.values()) {
                vpis[vi] = vpi;
                vi++;
            }
            Arrays.sort(vpis);
            for (int i = 0; i < vpis.length; i++) {
                vpis[i].setIndex(i);
            }
            // determine types of new Metanode in- and outports:
            // (note that we reach directly into the Node to get the port type
            //  so we need to correct the index for the - then missing - var
            //  port.)
            PortType[] exposedIncomingPortTypes
                                    = new PortType[exposedIncomingPorts.size()];
            for (Map.Entry<Pair<NodeID, Integer>, VerticalPortIndex> entry : exposedIncomingPorts.entrySet()) {
                Pair<NodeID, Integer> npi = entry.getKey();
                int index = entry.getValue().getIndex();
                NodeID nID = npi.getFirst();
                int portIndex = npi.getSecond();
                if (nID.equals(this.getID())) {
                    // if this connection comes directly from a Metanode Inport:
                    exposedIncomingPortTypes[index]
                                      = this.getInPort(portIndex).getPortType();
                } else {
                    // otherwise reach into Nodecontainer to find out port type:
                    NodeContainer nc = getNodeContainer(nID);
                    exposedIncomingPortTypes[index]
                                       = nc.getOutPort(portIndex).getPortType();
                }
            }
            PortType[] exposedOutportTypes
                                         = new PortType[exposedOutports.size()];
            for (Pair<NodeID, Integer> npi : exposedOutports.keySet()) {
                int index = exposedOutports.get(npi).getIndex();
                int portIndex = npi.getSecond();
                NodeContainer nc = getNodeContainer(npi.getFirst());
                exposedOutportTypes[index]
                                  = nc.getOutPort(portIndex).getPortType();
            }
            // create the new Metanode
            WorkflowManager newWFM = createAndAddSubWorkflow(
                    exposedIncomingPortTypes,
                    exposedOutportTypes, name);
            // move into center of nodes this one replaces...
            int x = 0;
            int y = 0;
            int count = 0;
            if (orgIDs.length >= 1) {
                for (int i = 0; i < orgIDs.length; i++) {
                    NodeContainer nc = getNodeContainer(orgIDs[i]);
                    NodeUIInformation uii = nc.getUIInformation();
                    if (uii != null) {
                        int[] bounds = uii.getBounds();
                        if (bounds.length >= 2) {
                            x += bounds[0];
                            y += bounds[1];
                            count++;
                        }
                    }
                }
            }
            if (count >= 1) {
                NodeUIInformation newUii =
                    new NodeUIInformation(x / count, y / count, -1, -1, true);
                newWFM.setUIInformation(newUii);
            }
            // copy the nodes into the newly create WFM:
            WorkflowCopyContent orgContent = new WorkflowCopyContent();
            orgContent.setNodeIDs(orgIDs);
            orgContent.setAnnotation(orgAnnos);
            WorkflowCopyContent newContent
                    = newWFM.copyFromAndPasteHere(this, orgContent);
            NodeID[] newIDs = newContent.getNodeIDs();
            Map<NodeID, NodeID> oldIDsHash = new HashMap<NodeID, NodeID>();
            for (int i = 0; i < orgIDs.length; i++) {
                oldIDsHash.put(orgIDs[i], newIDs[i]);
            }
            // move subworkflows into upper left corner but keep
            // original layout (important for undo!)
            // ATTENTION: if you change this, make sure it is revertable
            // by extractMetanode (and correctly so!).
            int xmin = Integer.MAX_VALUE;
            int ymin = Integer.MAX_VALUE;
            if (newIDs.length >= 1) {
                // calculate shift
                for (int i = 0; i < newIDs.length; i++) {
                    NodeContainer nc = newWFM.getNodeContainer(newIDs[i]);
                    NodeUIInformation uii = nc.getUIInformation();
                    if (uii != null) {
                        int[] bounds = uii.getBounds();
                        if (bounds.length >= 2) {
                            xmin = Math.min(bounds[0], xmin);
                            ymin = Math.min(bounds[1], ymin);
                        }
                    }
                }
                int xshift = 150 - Math.max(xmin, 70);
                int yshift = 120 - Math.max(ymin, 20);
                // move new nodes
                for (int i = 0; i < newIDs.length; i++) {
                    NodeContainer nc = newWFM.getNodeContainer(newIDs[i]);
                    NodeUIInformation uii = nc.getUIInformation();
                    if (uii != null) {
                        NodeUIInformation newUii =
                            uii.createNewWithOffsetPosition(
                                   new int[]{xshift, yshift});
                        nc.setUIInformation(newUii);
                    }
                }
                // move new annotations
                for (Annotation anno : newWFM.m_annotations) {
                    anno.shiftPosition(xshift, yshift);
                }
                // move bendpoints of all internal connections
                for (ConnectionContainer cc : newWFM.getConnectionContainers()) {
                    if ((!cc.getSource().equals(newWFM.getID()))
                            && (!cc.getDest().equals(newWFM.getID()))) {
                        ConnectionUIInformation uii = cc.getUIInfo();
                        if (uii != null) {
                            ConnectionUIInformation newUI =
                                uii.createNewWithOffsetPosition(
                                        new int[] {xshift, yshift});
                            cc.setUIInfo(newUI);
                        }
                    }
                }
            }
            // create connections INSIDE the new workflow (from incoming ports)
            for (ConnectionContainer cc : inportConnections.keySet()) {
                int portIndex = inportConnections.get(cc).getIndex();
                NodeID newID = oldIDsHash.get(cc.getDest());
                newWFM.addConnection(newWFM.getID(), portIndex,
                        newID, cc.getDestPort());
                this.removeConnection(cc);
            }
            // create connections INSIDE the new workflow (to outgoing ports)
            for (Pair<NodeID, Integer> npi : exposedOutports.keySet()) {
                int index = exposedOutports.get(npi).getIndex();
                NodeID newID = oldIDsHash.get(npi.getFirst());
                newWFM.addConnection(newID, npi.getSecond(), newWFM.getID(), index);
            }
            // create OUTSIDE connections to the new workflow
            for (Pair<NodeID, Integer> npi : exposedIncomingPorts.keySet()) {
                int index = exposedIncomingPorts.get(npi).getIndex();
                this.addConnection(npi.getFirst(), npi.getSecond(),
                        newWFM.getID(), index);
            }
            // create OUTSIDE connections from the new workflow
            for (NodeID id : orgIDs) {
                // convert to a seperate array so we can delete connections!
                ConnectionContainer[] cca = new ConnectionContainer[0];
                cca = m_workflow.getConnectionsBySource(id).toArray(cca);
                for (ConnectionContainer cc : cca) {
                    if (!orgIDsHash.contains(cc.getDest())) {
                        Pair<NodeID, Integer> npi
                                = new Pair<NodeID, Integer>(cc.getSource(),
                                                            cc.getSourcePort());
                        int newPort = exposedOutports.get(npi).getIndex();
                        this.removeConnection(cc);
                        this.addConnection(newWFM.getID(), newPort,
                                cc.getDest(), cc.getDestPort());
                    }
                }
            }
            // and finally: delete the original nodes and annotations.
            for (NodeID id : orgIDs) {
                this.removeNode(id);
            }
            for (WorkflowAnnotation anno : orgAnnos) {
                this.removeAnnotation(anno);
            }
            return newWFM;
        }
    }

    /** check if node can be safely reset. In case of a WFM we will check
     * if one of the internal nodes can be reset and none of the nodes
     * are "in progress".
     *
     * @return if all internal nodes can be reset.
     */
    @Override
    boolean isResetable() {
        // first check if there is a node in execution
        for (NodeContainer nc : m_workflow.getNodeValues()) {
            if (nc.getInternalState().isExecutionInProgress()) {
                return false;
            }
        }
        // check for through connection
        for (ConnectionContainer cc
                : m_workflow.getConnectionsBySource(getID())) {
            if (cc.getType().equals(
                    ConnectionContainer.ConnectionType.WFMTHROUGH)) {
                return true;
            }
        }
        // check for at least one resetable node!
        for (NodeContainer nc : m_workflow.getNodeValues()) {
            if (nc.isResetable()) {
                return true;
            }
        }
        // nothing of the above: false.
        return false;
    }

    /** {@inheritDoc} */
    @Override
    boolean canPerformReset() {
        synchronized (m_workflowMutex) {
            final Collection<NodeContainer> nodeValues = m_workflow.getNodeValues();
            // no nodes but executed (through connections and outputs populated - see checkForNodeStateChanges)
            if (nodeValues.isEmpty() && getInternalState().isExecuted()) {
                return true;
            }
            // check for at least one executed and resetable node!
            for (NodeContainer nc : nodeValues) {
                if (nc.getInternalState().isExecutionInProgress()) {
                    return false;
                }
                if (nc.canPerformReset()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    void markForExecution(final boolean flag) {
        assert !isLocalWFM() : "Setting execution mark on meta node not allowed"
            + " for locally executing (sub-)flows";
        if (getInternalState().isExecutionInProgress()) {
            throw new IllegalStateException("Execution of (sub-)flow already "
                    + "in progress, current state is " + getInternalState());
        }
        markForExecutionAllNodesInWorkflow(flag);
        setInternalState(InternalNodeContainerState.CONFIGURED_MARKEDFOREXEC);
    }

    /** {@inheritDoc} */
    @Override
    void mimicRemoteExecuting() {
        synchronized (m_workflowMutex) {
            for (NodeContainer nc : m_workflow.getNodeValues()) {
                nc.mimicRemoteExecuting();
            }
            // do not propagate -- this method is called from parent
            checkForNodeStateChanges(false);
        }
    }

    /** {@inheritDoc} */
    @Override
    void mimicRemotePreExecute() {
        synchronized (m_workflowMutex) {
            for (NodeContainer nc : m_workflow.getNodeValues()) {
                nc.mimicRemotePreExecute();
            }
            // do not propagate -- this method is called from parent
            checkForNodeStateChanges(false);
        }
    }

    /** {@inheritDoc} */
    @Override
    void mimicRemotePostExecute() {
        synchronized (m_workflowMutex) {
            for (NodeContainer nc : m_workflow.getNodeValues()) {
                nc.mimicRemotePostExecute();
            }
            // do not propagate -- this method is called from parent
            checkForNodeStateChanges(false);
        }
    }

    /** {@inheritDoc} */
    @Override
    void mimicRemoteExecuted(final NodeContainerExecutionStatus status) {
        synchronized (m_workflowMutex) {
            for (NodeContainer nc : m_workflow.getNodeValues()) {
                int i = nc.getID().getIndex();
                NodeContainerExecutionStatus sub = status.getChildStatus(i);
                if (sub == null) {
                    assert false : "Execution status is null for child " + i;
                    sub = NodeContainerExecutionStatus.FAILURE;
                }
                // will be ignored on already executed nodes
                // (think of an executed file reader in a meta node that is
                // submitted onto a cluster in the executed state already)
                nc.mimicRemoteExecuted(sub);
            }
            // do not propagate -- method is (indirectly) called from parent
            checkForNodeStateChanges(false);
        }
    }

    /** {@inheritDoc} */
    @Override
    boolean performStateTransitionQUEUED() {
        assert !isLocalWFM() : "Not to be called on workflow manager running locally (expected different job manager)";
        assert Thread.holdsLock(m_nodeMutex);
        // switch state from marked to queued
        switch (getInternalState()) {
        // also allow unconfigured-mark as there may be configured_marked nodes also
        case UNCONFIGURED_MARKEDFOREXEC:
        case CONFIGURED_MARKEDFOREXEC:
            setInternalState(InternalNodeContainerState.CONFIGURED_QUEUED);
            break;
        case EXECUTED_MARKEDFOREXEC:
            setInternalState(InternalNodeContainerState.EXECUTED_QUEUED);
            break;
        default:
            throwIllegalStateException();
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    boolean performStateTransitionPREEXECUTE() {
        assert !isLocalWFM() : "Execution of meta node not allowed"
            + " for locally executing (sub-)flows";
        synchronized (m_nodeMutex) {
            if (getInternalState().isExecutionInProgress()) {
                for (NodeContainer nc : m_workflow.getNodeValues()) {
                    nc.mimicRemotePreExecute();
                }
                // method is called from parent, don't propagate state changes
                checkForNodeStateChanges(false);
                return true;
            } else {
                // node may not be executinInProgress when previously queued
                // but then canceled upon user request; this method is called
                // from a worker thread, which does not know about cancelation
                // yet (it is interrupted when run as local job ... but this
                // isn't a local job)
                return false;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    void performStateTransitionEXECUTING() {
        assert !isLocalWFM() : "Execution of meta node not allowed"
            + " for locally executing (sub-)flows";
        synchronized (m_nodeMutex) {
            for (NodeContainer nc : m_workflow.getNodeValues()) {
                nc.mimicRemoteExecuting();
            }
            // method is called from parent, don't propagate state changes
            checkForNodeStateChanges(false);
        }
    }

    /** {@inheritDoc} */
    @Override
    void performStateTransitionPOSTEXECUTE() {
        assert !isLocalWFM() : "Execution of meta node not allowed"
            + " for locally executing (sub-)flows";
        synchronized (m_nodeMutex) {
            for (NodeContainer nc : m_workflow.getNodeValues()) {
                nc.mimicRemotePostExecute();
            }
            // method is called from parent, don't propagate state changes
            checkForNodeStateChanges(false);
        }
    }

    /** {@inheritDoc} */
    @Override
    void performStateTransitionEXECUTED(final NodeContainerExecutionStatus status) {
        assert !isLocalWFM() : "Execution of meta node not allowed for locally executing (sub-)flows";
        synchronized (m_nodeMutex) {
            mimicRemoteExecuted(status);
            String stateList = printNodeSummary(getID(), 0);
            // this method is called from the parent's doAfterExecute
            // we don't propagate state changes (i.e. argument flag is false)
            // since the check for state changes in the parent will happen next
            if (!sweep(m_workflow.getNodeIDs(), false)) {
                LOGGER.debug("Some states were invalid, old states are:");
                LOGGER.debug(stateList);
                LOGGER.debug("The new (corrected) states are: ");
                LOGGER.debug(printNodeSummary(getID(), 0));
            }
            // allow failed nodes (IDLE) to be configured
            configureAllNodesInWFM(/*keepNodeMessage=*/true);
        }
    }

    /* ------------- node commands -------------- */

    /**
     * Check if a node can be reset, meaning that it is executed and all of
     * its successors are idle or executed as well. We do not want to mess
     * with executing chains.
     *
     * @param nodeID the id of the node
     * @return true if the node can safely be reset.
     */
    public boolean canResetNode(final NodeID nodeID) {
        synchronized (m_workflowMutex) {
            NodeContainer nc = m_workflow.getNode(nodeID);
            if (nc == null) {
                return false;
            }
            // (a) this node is resetable
            // (b) no successors is running or queued.
            // (c) not contained in a sub node that has executing successors
            return nc.canPerformReset() && !hasSuccessorInProgress(nodeID) && canResetContainedNodes();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean canResetContainedNodes() {
        // workflows and meta nodes can always reset their interna (under canReset(NodeID)) unless the parent forbids it
        return isProject() || getDirectNCParent().canResetContainedNodes();
    }


    /** Called from virtual end of sub node to determine if there are any executing downstream nodes.
     * @param nodeID the id to query (sub node ID)
     * @return ... */
    boolean canResetSuccessors(final NodeID nodeID) {
        synchronized (m_workflowMutex) {
            return !hasSuccessorInProgress(nodeID);
        }
    }

    // TODO: This function needs to go!
    // replaced by invokeResetOnSNC and invokeResetOnPortSuccessors...
    @Deprecated
    private void invokeResetOnNode(final NodeID nodeID) {
        assert Thread.holdsLock(m_workflowMutex);
        NodeContainer nc = getNodeContainer(nodeID);
        if (nc.getInternalState().isExecuted()) {
            if (nc instanceof SingleNodeContainer) {
                invokeResetOnSingleNodeContainer((SingleNodeContainer)nc);
            } else {
                // TODO - this case should never happen but can not yet be
                // guaranteed since Bernd's persistor grap calls it left and right.
                assert nc instanceof WorkflowManager;
                ((WorkflowManager)nc).resetAllNodesInWFM();
            }
        }
    }

    /** Reset node and notify listeners. */
    private void invokeResetOnSingleNodeContainer(final SingleNodeContainer snc) {
        assert Thread.holdsLock(m_workflowMutex);
        snc.reset();
        if (snc.isModelCompatibleTo(LoopStartNode.class)) {
            ((NativeNodeContainer)snc).getNode().setLoopEndNode(null);
        }
        if (snc.isModelCompatibleTo(LoopEndNode.class)) {
            ((NativeNodeContainer)snc).getNode().setLoopStartNode(null);
        }
    }

    /**
     * Test if successors of a node are currently executing.
     *
     * @param nodeID id of node
     * @return true if at least one successors is currently in progress.
     */
    private boolean hasSuccessorInProgress(final NodeID nodeID) {
        assert Thread.holdsLock(m_workflowMutex);
        if (this.getID().equals(nodeID)) {  // we are talking about this WFM
            return getParent().hasSuccessorInProgress(nodeID);
        }
        NodeContainer nc = m_workflow.getNode(nodeID);
        if (nc == null) {
            // node has disappeared. Fixes bug 4881: a editor of a deleted meta node updates the enable status
            // of its action buttons one last time
            return false;
        }
        // get all successors of the node, including the WFM itself
        // if there are outgoing connections:
        LinkedHashMap<NodeID, Set<Integer>> nodes
        = m_workflow.getBreadthFirstListOfNodeAndSuccessors(nodeID, false);
        // make sure we only consider successors, not node itself!
        // (would result in strange effects when we step out of the metanode with
        //  executing predecessors of the original node)
        nodes.remove(nodeID);
        for (NodeID id : nodes.keySet()) {
            if (this.getID().equals(id)) {
                // skip outgoing connections for now (handled below)
            } else {
                NodeContainer currentNC = getNodeContainer(id);
                if (currentNC.getInternalState().isExecutionInProgress()) {
                    return true;
                }
            }
        }
        // now also check successors of the metanode itself
        if (nodes.keySet().contains(this.getID())) {
            // TODO check only nodes connection to the specific WF outport
            if (getParent().hasSuccessorInProgress(getID())) {
                return true;
            }
        }
        return false;
    }

    //////////////////////////////////////////////////////////
    // NodeContainer implementations (WFM acts as meta node)
    //////////////////////////////////////////////////////////

    /** Reset node and all executed successors of a specific node.
     * Note that we will reset & configure(!) nodes apart of the (in)direct
     * successors if the list contains loopend nodes without their
     * corresponding loopstart equivalents.
     *
     * @param id of first node in chain to be reset.
     */
    private void resetNodeAndSuccessors(final NodeID id) {
        assert Thread.holdsLock(m_workflowMutex);
        NodeContainer nc = getNodeContainer(id);
        if (!nc.isResetable()) {
            if (nc.getInternalState().equals(InternalNodeContainerState.IDLE)) {
                // the node is IDLE: we don't need to reset it but we
                // should remove its node message! (This for instance
                // matters when we disconnect the inport of this node
                // and it showed an error due to conflicting stacks!)
                nc.setNodeMessage(null);
            }
            // any other reasons for "non reset-ability" are ignored until
            // now (Mar 2012) - not sure if there could be others?
            return;
        }
        // clean context - that is make sure all loops affected by
        // this reset-"chain" are completely reset/configured!
        resetAndConfigureAffectedLoopContext(id);
        if (!nc.isResetable()) {
            // if the above led to an implicit reset of our node: stop here.
            return;
        }
        // Now perform the actual reset!
        // a) Reset all successors first
        resetSuccessors(id);
        if (!nc.isResetable()) {
            // if the above led to an implicit reset of our node
            // (contained in loop): stop here.
            return;
        }
        // b) and then reset node itself
        if (nc instanceof SingleNodeContainer) {
            invokeResetOnSingleNodeContainer((SingleNodeContainer)nc);
        } else {
            WorkflowManager wfm = (WorkflowManager)nc;
            // this is ok, since we will never call this again
            // while traversing a flow - this is the main entry
            // point from the outside and should reset all children
            // (resetSuccessors() follows ports and will be
            // called throughout subsequent calls...)

            // TODO this configures the meta node, too!
            wfm.resetAndReconfigureAllNodesInWFM();
        }
        nc.resetJobManagerViews();
    }

    /** Check all successors and the given node and make sure that
     * all loops that are partially contained in the set of
     * successors are completely reset and freshly configured. This
     * is used to ensure proper reset/configure of the entire loop
     * if only parts of a loop are affected by a reset propagation.
     *
     * @param id ...
     */
    private void resetAndConfigureAffectedLoopContext(final NodeID id) {
        // First find all the nodes in this workflow that are connected
        // to the origin:
        LinkedHashMap<NodeID, Set<Integer>> allnodes
               = m_workflow.getBreadthFirstListOfNodeAndSuccessors(id, /*skipWFM=*/ true);
        // the do cleanup
        resetAndConfigureAffectedLoopContext(allnodes);
    }

    /** Check only successors of the given node/ports and make sure that
     * all loops that are partially contained in the set of
     * successors are completely reset and freshly configured. This
     * is used to ensure proper reset/configure of the entire loop
     * if only parts of a loop are affected by a reset propagation.
     *
     * @param id ...
     * @param portIndices set of output ports, empty set for all ports.
     */
    private void resetAndConfigureAffectedLoopContext(final NodeID id, final Set<Integer>portIndices) {
        // First find all the nodes in this workflow that are connected
        // to the origin:
        LinkedHashMap<NodeID, Set<Integer>> allnodes
               = m_workflow.getBreadthFirstListOfPortSuccessors(id, portIndices, /*skipWFM=*/ true);
        // the do cleanup
        resetAndConfigureAffectedLoopContext(allnodes);
    }

    /* Check list of nodes and reset/configure all loops that are only
     * partially contained in the list.
     *
     * @param allnodes ...
     */
    private void resetAndConfigureAffectedLoopContext(final LinkedHashMap<NodeID, Set<Integer>> allnodes) {
        // find any LoopEnd nodes without loop starts in the set:
        for (NodeID leid : allnodes.keySet()) {
            NodeContainer lenc = getNodeContainer(leid);
            if (lenc instanceof NativeNodeContainer) {
                if (((NativeNodeContainer)lenc).getNodeModel() instanceof LoopEndNode) {
                    NodeID lsid;
                    try {
                        lsid = m_workflow.getMatchingLoopStart(leid);
                    } catch (Exception e) {
                        // this can happen if we run into incorrectly configured loops
                        lsid = null;
                    }
                    if ((lsid != null) && (!allnodes.containsKey(lsid))) {
                        // found a LoopEndNode without matching LoopStart
                        // to be reset as well: try to reset&configure the
                        // node (and its successors) if it is executed and
                        // we are past the first iteration (=corresponding
                        // End loop was executed at least once already - which
                        // also means it is set in the LoopContextObject):
                        NativeNodeContainer lsnc = (NativeNodeContainer)m_workflow.getNode(lsid);
                        if (InternalNodeContainerState.EXECUTED.equals(lsnc.getInternalState())) {
                            FlowLoopContext flc = lsnc.getOutgoingFlowObjectStack().peek(FlowLoopContext.class);
                            if (flc.needsCompleteResetOnLoopBodyChanges()) {
                                // this is ugly but necessary: we need to make
                                // sure we don't go into an infinite loop here,
                                // trying to reset this part over and over again.
                                // so reset this node "out of the order" first
                                // as a "flag" that we have already done it:
                                invokeResetOnSingleNodeContainer(lsnc);
                                configureSingleNodeContainer(lsnc, true);
                                // and now launch the proper reset (&configure!) for this branch:
                                // Fix for bug #4148:
                                // instead of a call to resetAndConfigureNode(lsid)
                                // call the following to avoid checking for "isResetable()"
                                // which will fail in nested loops with "affected" loops
                                // within a metanode
                                resetSuccessors(lsid);
                                // and launch configure starting with this node
                                configureNodeAndSuccessors(lsid, false);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Reset node and all executed successors of a specific node and
     * launch configure storm.
     *
     * @param id of first node in chain to be reset.
     */
    public void resetAndConfigureNode(final NodeID id) {
        resetAndConfigureNodeAndSuccessors(id, true);
    }

    /** Reset node and all executed successors of a specific node and
     * launch configure storm.
     *
     * @param id of first node in chain to be reset.
     * @param resetMyself If to include the node itself or only downstream nodes
     */
    void resetAndConfigureNodeAndSuccessors(final NodeID id, final boolean resetMyself) {
        synchronized (m_workflowMutex) {
            if (hasSuccessorInProgress(id)) {
                throw new IllegalStateException("Cannot reset node (wrong state of node or successors) " + id);
            }
            if (resetMyself) {
                resetNodeAndSuccessors(id);
            } else {
                // TODO does it need a reset-loop context, too (see resetNodeAndSuccessors)
                resetSuccessors(id);
            }
            // and launch configure starting with this node
            configureNodeAndSuccessors(id, resetMyself);
            checkForNodeStateChanges(true);
        }
    }

    /**
     * Reset successors of a node. Do not reset node itself since it
     * may be a metanode from within we started this.
     *
     * @param id of node
     */
    private void resetSuccessors(final NodeID id) {
        resetSuccessors(id, -1);
    }

    /**
     * Reset successors of a node connected to a specific out port.
     * @param id The id in question
     * @param portID port index or -1 to reset successors connected to any out port.
     */
    void resetSuccessors(final NodeID id, final int portID) {
        assert Thread.holdsLock(m_workflowMutex);
        assert !this.getID().equals(id);
        Set<ConnectionContainer> succs = m_workflow.getConnectionsBySource(id);
        for (ConnectionContainer conn : succs) {
            NodeID currID = conn.getDest();
            if ((conn.getSourcePort() == portID) || (portID < 0)) {
                // only reset successors if they are connected to the
                // correct port or we don't care (id==-1)
                if (!conn.getType().isLeavingWorkflow()) {
                    assert m_workflow.getNode(currID) != null;
                    // normal connection to another node within this workflow
                    // first check if it is already reset
                    NodeContainer nc = m_workflow.getNode(currID);
                    assert nc != null;
                    if (nc.isResetable()) {
                        // first reset successors of successor
                        if (nc instanceof SingleNodeContainer) {
                            // for a normal node, ports don't matter
                            this.resetSuccessors(currID, -1);
                            // ..then reset immediate successor itself if it was not implicitly
                            // reset by the successor reset (via an outer loop)
                            if (nc.isResetable()) {
                                invokeResetOnSingleNodeContainer((SingleNodeContainer)nc);
                            }
                        } else {
                            assert nc instanceof WorkflowManager;
                            WorkflowManager wfm = (WorkflowManager)nc;
                            // first reset all nodes which are connected
                            // to the outports of interest of this WFM...
                            Set<Integer> outcomingPorts = wfm.m_workflow.connectedOutPorts(conn.getDestPort());
                            for (Integer i : outcomingPorts) {
                                this.resetSuccessors(currID, i);
                            }
                            // Reset nodes inside WFM.
                            // (cleaning of  loop context affected one level
                            //  down will be done in there.)
                            wfm.resetNodesInWFMConnectedToInPorts(
                                    Collections.singleton(conn.getDestPort()));
                        }
                    }
                } else {
                    assert this.getID().equals(currID);
                    // connection goes to a meta outport!
                    // Only reset nodes which are connected to the currently
                    // interesting port.
                    int outGoingPortID = conn.getDestPort();
                    // clean loop context affected one level up
                    getParent().resetAndConfigureAffectedLoopContext(
                           this.getID(), Collections.singleton(outGoingPortID));
                    getParent().resetSuccessors(this.getID(), outGoingPortID);
                }
            }
        }
        checkForNodeStateChanges(true);
    }

    /** {@inheritDoc}
     * @since 2.11*/
    @Override
    public boolean canConfigureNodes() {
        synchronized (m_workflowMutex) {
            // workflows and meta nodes can always configure, nodes in subnodes need to ask parent
            return isProject() || getDirectNCParent().canConfigureNodes();
        }
    }

    /** Check if a node can be executed directly.
     *
     * @param nodeID id of node
     * @return true if node is configured and all immediate predecessors are executed.
     * @since 2.9
     */
    public boolean canExecuteNodeDirectly(final NodeID nodeID) {
        synchronized (m_workflowMutex) {
            NodeContainer nc = m_workflow.getNode(nodeID);
            if (nc == null) {
                return false;
            }
            // don't allow individual execution of nodes in a remote exec flow
            if (!isLocalWFM()) {
                return false;
            }
            // check for WorkflowManager - which we handle differently
            if (nc instanceof WorkflowManager) {
                return ((WorkflowManager)nc).hasExecutableNode();
            } else if (nc instanceof SubNodeContainer) {
                return ((SubNodeContainer)nc).getWorkflowManager().hasExecutableNode();
            }
            return nc.getInternalState().equals(InternalNodeContainerState.CONFIGURED);
        }
    }

    /** Check if a node can be executed either directly or via chain of nodes that
     * include an executable node.
    *
    * @param nodeID id of node
    * @return true if node can be executed.
    */
   public boolean canExecuteNode(final NodeID nodeID) {
       synchronized (m_workflowMutex) {
           NodeContainer nc = m_workflow.getNode(nodeID);
           if (nc == null) {
               return false;
           }
           // check node itself:
           if (canExecuteNodeDirectly(nodeID)) {
               return true;
           }
           // check predecessors:
           return hasExecutablePredecessor(nodeID);
       }
   }

   /**
    * Test if any of the predecessors of the given node is executable (=configured).
    *
    * @param nodeID id of node
    * @return true if at least one predecessor can be executed.
    */
    private boolean hasExecutablePredecessor(final NodeID nodeID) {
        assert Thread.holdsLock(m_workflowMutex);
        if (this.getID().equals(nodeID)) {  // we are talking about this WFM
            return getParent().hasExecutablePredecessor(nodeID);
        }
        if (m_workflow.getNode(nodeID) == null) {
            // node has disappeared. Fixes bug 4881: a editor of a deleted meta node updates the enable status
            // of its action buttons one last time
            return false;
        }
        // get all predeccessors of the node, including the WFM itself if there are  connections:
        Set<NodeID> nodes = m_workflow.getPredecessors(nodeID);
        for (NodeID id : nodes) {
            if (this.getID().equals(id)) {
                // skip outgoing connections for now (handled below)
            } else {
                if (canExecuteNodeDirectly(id)) {
                    return true;
                }
            }
        }
        // now also check predecessors of the metanode itself
        if (nodes.contains(this.getID())) {
            // TODO check only nodes connection to the specific WF inport
            if (getParent().hasExecutablePredecessor(getID())) {
                return true;
            }
        }
        return false;
    }



    /** Returns true if all required input data is available to the node.
     * Unconnected optional inputs are OK.
     *
     * <p>Used for configuration of data aware configuration dialogs which
     * need the input data prior opening the dialog.
     * @param nodeID Node in question.
     * @return That property.
     * @since 2.6 */
    boolean isAllInputDataAvailableToNode(final NodeID nodeID) {
        synchronized (m_workflowMutex) {
            final NodeContainer nc = getNodeContainer(nodeID);
            return assembleInputData(nodeID, new PortObject[nc.getNrInPorts()]);
        }
    }

    /** Check if a node can be cancelled individually.
    *
    * @param nodeID id of node
    * @return true if node can be cancelled
    *
    */
   public boolean canCancelNode(final NodeID nodeID) {
       synchronized (m_workflowMutex) {
           NodeContainer nc = m_workflow.getNode(nodeID);
           if (nc == null) {
               return false;
           }
           // don't allow individual cancellation of nodes in a remote exec flow
           if (!isLocalWFM()) {
               return false;
           }
           if (!nc.getInternalState().isExecutionInProgress()) {
               return false;
           }
           return true;
       }
   }

    /** @return true if any node contained in this workflow is executable,
     * that is configured.
     */
    private boolean hasExecutableNode() {
        for (NodeContainer nc : m_workflow.getNodeValues()) {
            if (nc instanceof SingleNodeContainer) {
                if (nc.getInternalState().equals(InternalNodeContainerState.CONFIGURED)) {
                    return true;
                }
            } else {
                if (((WorkflowManager)nc).hasExecutableNode()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    void cancelExecution() {
        synchronized (m_workflowMutex) {
            NodeExecutionJob job = getExecutionJob();
            if (job != null) {
                // this is a remotely executed workflow, cancel its execution
                // and let the execution job take care of a state updates of
                // the contained nodes.
                job.cancel();
            } else {
                for (NodeContainer nc : m_workflow.getNodeValues()) {
                    if (nc.getInternalState().isExecutionInProgress()) {
                        nc.cancelExecution();
                    }
                }
                checkForNodeStateChanges(true);
            }
        }
    }

    /**
     * Cancel execution of the given NodeContainer.
     *
     * @param nc node to be canceled
     */
    public void cancelExecution(final NodeContainer nc) {
        synchronized (m_workflowMutex) {
            disableNodeForExecution(nc.getID());
            if (nc.getInternalState().isExecutionInProgress()) {
                nc.cancelExecution();
            }
            checkForNodeStateChanges(true);
        }
    }

    /**
     * Pause loop execution of the given NodeContainer (=loop end).
     *
     * @param nc node to be canceled
     */
    public void pauseLoopExecution(final NodeContainer nc) {
        if (nc instanceof NativeNodeContainer) {
            NativeNodeContainer nnc = (NativeNodeContainer)nc;
            if (nnc.isModelCompatibleTo(LoopEndNode.class)) {
                synchronized (m_workflowMutex) {
                    if (nnc.getLoopStatus().equals(LoopStatus.RUNNING)) {
                        // currently running
                        nnc.pauseLoopExecution(true);
                    }
                    checkForNodeStateChanges(true);
                }
            }
        }
    }

    /** Resume operation of a paused loop. Depending on the flag we
     * either step (= run only one iteration and pause again) or run
     * until the loop is finished.
     *
     * @param nc The node container
     * @param oneStep If execution should only be resumed by one step
     */
    public void resumeLoopExecution(final NodeContainer nc, final boolean oneStep) {
        if (nc instanceof NativeNodeContainer) {
            NativeNodeContainer nnc = (NativeNodeContainer)nc;
            if (nnc.isModelCompatibleTo(LoopEndNode.class)) {
                synchronized (m_workflowMutex) {
                    if (nnc.getLoopStatus().equals(LoopStatus.PAUSED)) {
                        // currently paused - ok!
                        FlowLoopContext flc = nnc.getNode().getLoopContext();
                        try {
                            if (!oneStep) {
                                nnc.pauseLoopExecution(false);
                            }
                            restartLoop(flc);
                        } catch (IllegalLoopException ile) {
                            nc.setNodeMessage(new NodeMessage(NodeMessage.Type.ERROR, ile.getMessage()));
                        }
                    }
                }
            }
        }
    }

    /** Is the node with the given ID ready to take a new job manager. This
     * is generally true if the node is currently not executing.
     * @param nodeID The node in question.
     * @return Whether it's save to invoke the
     * {@link #setJobManager(NodeID, NodeExecutionJobManager)} method.
     */
    public boolean canSetJobManager(final NodeID nodeID) {
        synchronized (m_workflowMutex) {
            if (!m_workflow.containsNodeKey(nodeID)) {
                return false;
            }
            NodeContainer nc = getNodeContainer(nodeID);
            switch (nc.getInternalState()) {
            case CONFIGURED_QUEUED:
            case EXECUTED_QUEUED:
            case PREEXECUTE:
            case EXECUTING:
            case EXECUTINGREMOTELY:
            case POSTEXECUTE:
                return false;
            default:
                return true;
            }
        }
    }

    /** Sets a new job manager on the node with the given ID.
     * @param nodeID The node in question.
     * @param jobMgr The new job manager (may be null to use parent's one).
     * @throws IllegalStateException If the node is not ready
     * @throws IllegalArgumentException If the node is unknown
     * @see #canSetJobManager(NodeID)
     */
    public void setJobManager(
            final NodeID nodeID, final NodeExecutionJobManager jobMgr) {
        synchronized (m_workflowMutex) {
            NodeContainer nc = getNodeContainer(nodeID);
            nc.setJobManager(jobMgr);
        }
    }

    /** Attempts to cancel or running nodes in preparation for a removal of
     * this node (or its parent) from the root. Executing nodes, which can be
     * disconnected from the execution (e.g. remote cluster execution) are
     * disconnected if their status has been saved before.
     */
    public void shutdown() {
        performShutdown();
    }

    /** {@inheritDoc} */
    @Override
    void performShutdown() {
        synchronized (m_workflowMutex) {
            NodeExecutionJob job = getExecutionJob();
            if (job != null) {
                if (job.isSavedForDisconnect()) {
                    findJobManager().disconnect(job);
                } else {
                    cancelExecution();
                }
            } else {
                for (NodeContainer nc : m_workflow.getNodeValues()) {
                    disableNodeForExecution(nc.getID());
                    nc.performShutdown();
                }
                checkForNodeStateChanges(false);
            }
            m_wfmListeners.clear();
            super.performShutdown();
        }
    }

   /**
    * Convenience method: execute all and wait for execution to be done.
    *
    * @return true if execution was successful
    */
    public boolean executeAllAndWaitUntilDone() {
        if (this == ROOT) {
            throw new IllegalStateException("Can't execute ROOT workflow");
        }
        // let parent execute this node (important if job manager is assigned)
        // see also bug 2217
        getParent().executeUpToHere(getID());
        try {
            waitWhileInExecution(-1, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            LOGGER.warn("Thread interrupted while waiting for finishing execution");
        }
        return this.getInternalState().equals(InternalNodeContainerState.EXECUTED);
    }

    /** Causes the current thread to wait until the the workflow has reached
     * a non-executing state unless a given timeout elapsed.
     * @param time the maximum time to wait
     *       (0 or negative for waiting infinitely)
     * @param unit the time unit of the {@code time} argument
     * @return {@code false} if the waiting time detectably elapsed
     *         before return from the method, else {@code true}. It returns
     *         {@code true} if the time argument is 0 or negative.
     * @throws InterruptedException if the current thread is interrupted
     */
    public boolean waitWhileInExecution(final long time, final TimeUnit unit)
    throws InterruptedException {
        return waitWhileInExecution(new NodeContainer[] {this}, time, unit);
    }

    /** Causes the current thread to wait until the the workflow has reached
     * a non-executing state unless a given timeout elapsed.
     * @param time the maximum time to wait
     *       (0 or negative for waiting infinitely)
     * @param unit the time unit of the {@code time} argument
     * @return {@code false} if the waiting time detectably elapsed
     *         before return from the method, else {@code true}. It returns
     *         {@code true} if the time argument is 0 or negative.
     * @throws InterruptedException if the current thread is interrupted
     */
    private static boolean waitWhileInExecution(final NodeContainerStateObservable[] ncs,
            final long time, final TimeUnit unit)
        throws InterruptedException {
        // lock supporting timeout
        final ReentrantLock lock = new ReentrantLock();
        final Condition condition = lock.newCondition();
        NodeStateChangeListener listener = new NodeStateChangeListener() {
            @Override
            public void stateChanged(final NodeStateEvent stateEvent) {
                lock.lock();
                try {
                    if (!containsExecutingNode(ncs)) {
                        condition.signalAll();
                    }
                } finally {
                    lock.unlock();
                }
            }

        };
        lock.lockInterruptibly();
        for (NodeContainerStateObservable nc : ncs) {
            if (nc != null) {
                nc.addNodeStateChangeListener(listener);
            }
        }
        try {
            if (!containsExecutingNode(ncs)) {
                return true;
            }
            if (time > 0) {
                return condition.await(time, unit);
            } else {
                condition.await();
                return true;
            }
        } finally {
            lock.unlock();
            for (NodeContainerStateObservable nc : ncs) {
                if (nc != null) {
                    nc.removeNodeStateChangeListener(listener);
                }
            }
        }
    }

    /**
     * @param ncs
     * @return */
    private static boolean containsExecutingNode(final NodeContainerStateObservable[] ncs) {
        boolean isExecuting = false;
        for (NodeContainerStateObservable nc : ncs) {
            if (nc != null && nc.getNodeContainerState().isExecutionInProgress()) {
                isExecuting = true;
                break;
            }
        }
        return isExecuting;
    }

    /** Called by execute-all action to (attempt to) execute all nodes in the workflow. This is true when there is
     * at least one node that is executable (even though the state of the wfm is idle).
     * @return that property
     * @since 2.10 */
    public boolean canExecuteAll() {
        if (isLocalWFM()) {
            synchronized (m_workflowMutex) {
                return hasExecutableNode();
            }
        } else {
            // unsynchronized as parent may be ROOT (different mutex), see
            // bug 5722: Potential deadlock when a workflow is run with 3rd party executor
            WorkflowManager parent = getParent();
            return parent != null && parent.canExecuteNode(getID());
        }
    }

    /** (Try to) Execute all nodes in the workflow. This method only marks the end nodes for execution and then returns
     * immediately. If a job manager is set on the WFM this one will run the execution. In any case this method
     * returns immediately and does not wait for the execution to finish.
     * @see #executeAllAndWaitUntilDone() */
    public void executeAll() {
        if (isLocalWFM()) {
            synchronized (m_workflowMutex) {
                Set<NodeID> endNodes = new HashSet<NodeID>();
                for (NodeID id : m_workflow.getNodeIDs()) {
                    boolean hasNonParentSuccessors = false;
                    for (ConnectionContainer cc : m_workflow.getConnectionsBySource(id)) {
                        if (!cc.getDest().equals(this.getID())) {
                            hasNonParentSuccessors = true;
                            break;
                        }
                    }
                    if (!hasNonParentSuccessors) {
                        endNodes.add(id);
                    }
                }
                // now use these "end nodes" to start executing all until we
                // reach the beginning. Do NOT leave the workflow, though.
                Set<NodeID> executedNodes = new HashSet<NodeID>();
                while (endNodes.size() > 0) {
                    NodeID thisID = endNodes.iterator().next();
                    endNodes.remove(thisID);
                    // move all of the predecessors to the "end nodes"
                    for (ConnectionContainer cc : m_workflow.getConnectionsByDest(thisID)) {
                        NodeID nextID = cc.getSource();
                        if (!endNodes.contains(nextID) && !executedNodes.contains(nextID) && !nextID.equals(getID())) {
                            endNodes.add(nextID);
                        }
                    }
                    // try to execute the current node
                    NodeContainer nc = m_workflow.getNode(thisID);
                    if (nc.isLocalWFM()) {
                        assert nc instanceof WorkflowManager;
                        ((WorkflowManager)nc).executeAll();
                    } else {
                        executeUpToHere(thisID);
                    }
                    // and finally move the current node to the other list
                    executedNodes.add(thisID);
                }
                // bug 6026: need one additional check in case the workflow only consists of meta nodes
                // TODO: improve this: each and every node in the workflow performs a check!
                checkForNodeStateChanges(true);
            }
        } else {
            // unsynchronized as parent may be ROOT (different mutex), see
            // bug 5722: Potential deadlock when a workflow is run with 3rd party executor
            getParent().executeUpToHere(getID());
        }
    }

    /**
     * @param nc The node container
     * @param persistor The persistor
     * @return true if load and execution was successful, false otherwise
     * @throws InvalidSettingsException If the settings are invalid
     * @throws NodeExecutionJobReconnectException If continuing the execution does fail
     */
    boolean continueExecutionOnLoad(final NodeContainer nc,
            final NodeContainerPersistor persistor)
        throws InvalidSettingsException,  NodeExecutionJobReconnectException {
        NodeContainerMetaPersistor metaPers = persistor.getMetaPersistor();
        NodeSettingsRO execJobSettings = metaPers.getExecutionJobSettings();
        NodeOutPort[] ports = assemblePredecessorOutPorts(nc.getID());
        PortObject[] inData = new PortObject[ports.length];
        boolean allDataAvailable = true;
        for (int i = 0; i < ports.length; i++) {
            if (ports[i] != null) {
                inData[i] = ports[i].getPortObject();
                // if connected but no data, set to false
                if (inData[i] == null) {
                    allDataAvailable = false;
                }
            } else if (!nc.getInPort(i).getPortType().isOptional()) {
                // unconnected non-optional port ... abort
                allDataAvailable = false;
            }
        }
        if (allDataAvailable && nc.getInternalState().equals(InternalNodeContainerState.EXECUTINGREMOTELY)) {
            nc.continueExecutionOnLoad(inData, execJobSettings);
            return true;
        }
        return false;
    }

    /////////////////////////////////////////////////////////
    // WFM as NodeContainer: Dialog related implementations
    /////////////////////////////////////////////////////////

    private NodeDialogPane m_nodeDialogPane;

    /** {@inheritDoc} */
    @Override
    public boolean hasDialog() {
        int c = NodeExecutionJobManagerPool.getNumberOfJobManagersFactories();
        return c > 1 || findNodes(MetaNodeDialogNode.class, true).size() > 0;
    }

    /** {@inheritDoc} */
    @Override
    NodeDialogPane getDialogPaneWithSettings(final PortObjectSpec[] inSpecs,
            final PortObject[] inData) throws NotConfigurableException {
        NodeDialogPane dialogPane = getDialogPane();
        // find all quickform input nodes and update meta dialog
        Map<NodeID, MetaNodeDialogNode> nodes =
                findNodes(MetaNodeDialogNode.class, false);
        ((MetaNodeDialogPane) dialogPane).setQuickformNodes(nodes);
        NodeSettings settings = new NodeSettings("wfm_settings");
        saveSettings(settings);
        Node.invokeDialogInternalLoad(dialogPane, settings, inSpecs, inData,
                new FlowObjectStack(getID()),
                new CredentialsProvider(this, m_credentialsStore),
                getDirectNCParent().isWriteProtected());
        return dialogPane;
    }

    /** {@inheritDoc} */
    @Override
    NodeDialogPane getDialogPane() {
        if (m_nodeDialogPane == null) {
            if (hasDialog()) {
                // create meta node dialog with quickforms
                m_nodeDialogPane = new MetaNodeDialogPane();
                // workflow manager jobs can't be split
                m_nodeDialogPane.addJobMgrTab(SplitType.DISALLOWED);
            } else {
                throw new IllegalStateException("Workflow has no dialog");
            }
        }
        return m_nodeDialogPane;
    }

    /** {@inheritDoc} */
    @Override
    public boolean areDialogAndNodeSettingsEqual() {
        if (!hasDialog()) {
            return true;
        }
        String defName = "wfm_settings";
        NodeSettings origSettings = new NodeSettings(defName);
        saveSettings(origSettings);
        NodeSettings dialogSettings = new NodeSettings(defName);
        NodeContext.pushContext(this);
        try {
            getDialogPane().finishEditingAndSaveSettingsTo(dialogSettings);
        } catch (InvalidSettingsException e) {
            return false;
        } finally {
            NodeContext.removeLastContext();
        }
        return dialogSettings.equals(origSettings);
    }

    /////////////////////////////////
    // Private helper functions
    /////////////////////////////////

    /**
     * @return global table repository for this WFM.
     */
    HashMap<Integer, ContainerTable> getGlobalTableRepository() {
        return m_globalTableRepository;
    }

    /** @return the fileStoreHandlerRepository for this meta node or project. */
    WorkflowFileStoreHandlerRepository getFileStoreHandlerRepository() {
        return m_fileStoreHandlerRepository;
    }

    /**
     * Check if any internal nodes have changed state which might mean that
     * this WFM also needs to change its state...
     * @param propagateChanges Whether to also inform this wfm's parent if done
     * (true always except for loading)
     */
    void checkForNodeStateChanges(final boolean propagateChanges) {
        assert Thread.holdsLock(m_workflowMutex);
        int[] nrNodesInState = new int[InternalNodeContainerState.values().length];
        int nrNodes = 0;
        boolean internalNodeHasError = false;
        for (NodeContainer ncIt : m_workflow.getNodeValues()) {
            nrNodesInState[ncIt.getInternalState().ordinal()]++;
            nrNodes++;
            if ((ncIt.getNodeMessage() != null)
                    && (ncIt.getNodeMessage().getMessageType().equals(
                            NodeMessage.Type.ERROR))) {
                internalNodeHasError = true;
            }
        }
        // set summarization message if any of the internal nodes has an error
        if (internalNodeHasError) {
            setNodeMessage(new NodeMessage(
                    NodeMessage.Type.ERROR, "Error in sub flow."));
        } else {
            setNodeMessage(NodeMessage.NONE);
        }
        //
        assert nrNodes == m_workflow.getNrNodes();
        InternalNodeContainerState newState = InternalNodeContainerState.IDLE;
        // check if all outports are connected
        boolean allOutPortsConnected =
            getNrOutPorts() == m_workflow.getConnectionsByDest(
                    this.getID()).size();
        // check if we have complete Objects on outports
        boolean allPopulated = false;
        // ...and at the same time find the "smallest" common state of
        // all inports (useful when all internal nodes are green but we
        // have through connections)!
        InternalNodeContainerState inportState = InternalNodeContainerState.EXECUTED;
        if (allOutPortsConnected) {
            allPopulated = true;
            for (int i = 0; i < getNrOutPorts(); i++) {
                NodeOutPort nop = getOutPort(i).getUnderlyingPort();
                if (nop == null) {
                    allPopulated = false;
                    inportState = InternalNodeContainerState.IDLE;
                } else if (nop.getPortObject() == null) {
                    allPopulated = false;
                    switch (nop.getNodeState()) {
                    case IDLE:
                    case UNCONFIGURED_MARKEDFOREXEC:
                        inportState = InternalNodeContainerState.IDLE;
                        break;
                    default:
                        inportState = InternalNodeContainerState.CONFIGURED;
                    }
                }
            }
        }
        if (nrNodes == 0) {
            // special case: zero nodes!
            if (allOutPortsConnected) {
                newState = allPopulated ? InternalNodeContainerState.EXECUTED : InternalNodeContainerState.CONFIGURED;
            } else {
                newState = InternalNodeContainerState.IDLE;
            }
        } else if (nrNodesInState[InternalNodeContainerState.EXECUTED.ordinal()] == nrNodes) {
            // WFM is executed only if all (>=1) nodes are executed and
            // all output ports are connected and contain their
            // portobjects.
            if (allPopulated) {
                // all nodes in WFM done and all ports populated!
                newState = InternalNodeContainerState.EXECUTED;
            } else {
                // all executed but some through connections!
                newState = inportState;
            }
        } else if (nrNodesInState[InternalNodeContainerState.CONFIGURED.ordinal()] == nrNodes) {
            // all (>=1) configured
            if (allOutPortsConnected) {
                newState = InternalNodeContainerState.CONFIGURED;
            } else {
                newState = InternalNodeContainerState.IDLE;
            }
        } else if (nrNodesInState[InternalNodeContainerState.EXECUTED.ordinal()]
                                  + nrNodesInState[InternalNodeContainerState.CONFIGURED.ordinal()]
                                                   == nrNodes) {
            newState = InternalNodeContainerState.CONFIGURED;
        } else if (nrNodesInState[InternalNodeContainerState.EXECUTING.ordinal()] >= 1) {
            newState = InternalNodeContainerState.EXECUTING;
        } else if (nrNodesInState[InternalNodeContainerState.EXECUTINGREMOTELY.ordinal()] >= 1) {
            newState = InternalNodeContainerState.EXECUTINGREMOTELY;
        } else if (nrNodesInState[InternalNodeContainerState.PREEXECUTE.ordinal()] >= 1) {
            newState = InternalNodeContainerState.EXECUTING;
        } else if (nrNodesInState[InternalNodeContainerState.POSTEXECUTE.ordinal()] >= 1) {
            newState = InternalNodeContainerState.EXECUTING;
        } else if (nrNodesInState[InternalNodeContainerState.CONFIGURED_QUEUED.ordinal()] >= 1) {
            newState = InternalNodeContainerState.EXECUTING;
        } else if (nrNodesInState[InternalNodeContainerState.EXECUTED_QUEUED.ordinal()] >= 1) {
            newState = InternalNodeContainerState.EXECUTING;
        } else if (nrNodesInState[InternalNodeContainerState.UNCONFIGURED_MARKEDFOREXEC.ordinal()] >= 1) {
            newState = InternalNodeContainerState.UNCONFIGURED_MARKEDFOREXEC;
        } else if (nrNodesInState[InternalNodeContainerState.CONFIGURED_MARKEDFOREXEC.ordinal()]
                   + nrNodesInState[InternalNodeContainerState.EXECUTED_MARKEDFOREXEC.ordinal()] >= 1) {
            newState = InternalNodeContainerState.CONFIGURED_MARKEDFOREXEC;
        }
        InternalNodeContainerState oldState = this.getInternalState();
        this.setInternalState(newState, propagateChanges);
        boolean wasExecuting = oldState.equals(InternalNodeContainerState.EXECUTINGREMOTELY)
            || oldState.equals(InternalNodeContainerState.EXECUTING);
        if (wasExecuting) {
            boolean isExecuting = newState.isExecutionInProgress();
            if (newState.equals(InternalNodeContainerState.EXECUTED)) {
                // we just successfully executed this WFM: check if any
                // loops were waiting for this one in the parent workflow!
                if (getWaitingLoops().size() >= 1) {
                    // looks as if some loops were waiting for this node to
                    // finish! Let's try to restart them:
                    for (FlowLoopContext flc : getWaitingLoops()) {
                        try {
                            getParent().restartLoop(flc);
                        } catch (IllegalLoopException ile) {
                            // set error message in LoopEnd node not this one!
                            NodeMessage nm = new NodeMessage(NodeMessage.Type.ERROR, ile.getMessage());
                            getParent().disableLoopExecution(flc, nm);
                        }
                    }
                    clearWaitingLoopList();
                }
            } else if (!isExecuting) {
                // if something went wrong and any other loops were waiting
                // for this node: clean them up!
                // (most likely this is just an IDLE node, however, which
                // had other flows that were not executed (such as ROOT!)
                for (FlowLoopContext flc : getWaitingLoops()) {
                    getParent().disableLoopExecution(flc, null);
                }
                clearWaitingLoopList();
            }
        }
        if (inportState.equals(InternalNodeContainerState.IDLE)) {
            newState = InternalNodeContainerState.IDLE;
        }
        if (inportState.equals(InternalNodeContainerState.CONFIGURED)
                && (!newState.equals(InternalNodeContainerState.IDLE))
                && (!newState.equals(InternalNodeContainerState.UNCONFIGURED_MARKEDFOREXEC))) {
            newState = InternalNodeContainerState.CONFIGURED;
        }
        if ((!oldState.equals(newState)) && (getParent() != null) && propagateChanges) {
            // make sure parent WFM reflects state changes
            if (m_workflowMutex.equals(getDirectNCParent().getWorkflowMutex())
                    // simple: mutexes are the same which means that we have
                    // either in- or outgoing connections (or both). No need
                    // to add an synchronize on the parent-mutex.
                    || Thread.holdsLock(getDirectNCParent().getWorkflowMutex())) {
                    // the second case is less simple: we don't have the same
                    // mutex but we already hold it: in this case, do not
                    // make this change asynchronously because we obviously
                    // called this from outside the "disconnected" metanode
                    // and want to keep the state change sync'ed.
                    // this fixes a problem with metanodes containing cluster
                    // (sub) workflows which were started but the state of
                    // the metanode/project was changed too late.
                getParent().checkForNodeStateChanges(propagateChanges);
            } else {
                // Different mutexes, that is this workflowmanager is a
                // project and the state check in the parent has do be
                // done asynchronosly to avoid deadlocks.
                // Locking the parent here would be exactly what we do not want
                // to do: Never lock a child (e.g. node) first and then its
                // parent (e.g. wfm) - see also bug #1755!
                PARENT_NOTIFIER.execute(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (getParent().getWorkflowMutex()) {
                                getParent().checkForNodeStateChanges(propagateChanges);
                        }
                    }
                });
            }
        }
    }

    /** Assemble array of all NodeOutPorts connected to the input
     * ports of a given node. This routine will make sure to skip intermediate
     * metanode "bridges".
     *
     * @param id of node
     * @return array of NodeOutPorts connected to this node
     */
    private NodeOutPort[] assemblePredecessorOutPorts(final NodeID id) {
        NodeContainer nc = getNodeContainer(id);
        int nrIns = nc.getNrInPorts();
        NodeOutPort[] result = new NodeOutPort[nrIns];
        Set<ConnectionContainer> incoming = m_workflow.getConnectionsByDest(id);
        for (ConnectionContainer conn : incoming) {
            assert conn.getDest().equals(id);
            // get info about destination
            int destPortIndex = conn.getDestPort();
            int portIndex = conn.getSourcePort();
            if (conn.getSource() != this.getID()) {
                // connected to another node inside this WFM
                assert conn.getType() == ConnectionType.STD;
                result[destPortIndex] =
                    m_workflow.getNode(conn.getSource()).getOutPort(portIndex);
            } else {
                // connected to a WorkflowInport
                assert conn.getType() == ConnectionType.WFMIN;
                result[destPortIndex] = getWorkflowIncomingPort(portIndex);
            }
        }
        return result;
    }

    /**
     * Check if a node has fully connected incoming ports.
     *
     * @param id of Node
     * @return true if all input ports are connected.
     */
    boolean isFullyConnected(final NodeID id) {
        synchronized (m_workflowMutex) {
            if (id.equals(this.getID())) {
                return getParent().isFullyConnected(id);
            }
            NodeContainer nc = getNodeContainer(id);
            NodeOutPort[] predOutPorts = assemblePredecessorOutPorts(id);
            for (int i = 0; i < predOutPorts.length; i++) {
                NodeOutPort p = predOutPorts[i];
                if (p == null) { // unconnected port
                    // accept only if inport is optional
                    if (!nc.getInPort(i).getPortType().isOptional()) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /** Returns true if the argument represents a source node in the workflow.
     * A source node is a node, which has (i) no predecessors and (ii) only
     * optional inputs.
     * @param id The node to test -- must exist in workflow
     * @return If argument is a source node (configure can start from there)
     */
    private boolean isSourceNode(final NodeID id) {
        NodeContainer nc = getNodeContainer(id);
        NodeOutPort[] predPorts = assemblePredecessorOutPorts(id);
        for (int i = 0; i < predPorts.length; i++) {
            NodeInPort inPort = nc.getInPort(i);
            boolean isOptional = inPort.getPortType().isOptional();
            if (predPorts[i] != null) { // has connected predecessor
                return false;
            } else if (!isOptional) {   // not connected but required
                return false;
            }
        }
        return true;
    }

    /** Attempts to configure all nodes in the workflow. It will also try to
     * configure nodes whose predecessors did not change their output specs.
     * This method checks the new state of the meta node but
     * does not propagate it (since called recursively).
     * @param keepNodeMessage Whether to retain the previously set node message.
     */
    private void configureAllNodesInWFM(final boolean keepNodeMessage) {
        assert Thread.holdsLock(m_workflowMutex);
        Set<NodeID> bfsSortedSet = m_workflow.createBreadthFirstSortedList(
                m_workflow.getNodeIDs(), true).keySet();
        for (NodeID id : bfsSortedSet) {
            NodeContainer nc = getNodeContainer(id);
            if (nc instanceof SingleNodeContainer) {
                switch (nc.getInternalState()) {
                case EXECUTED:
                    break;
                default:
                    configureSingleNodeContainer(
                            (SingleNodeContainer)nc, keepNodeMessage);
                }
            } else {
                ((WorkflowManager)nc).configureAllNodesInWFM(keepNodeMessage);
            }
        }
        checkForNodeStateChanges(false);
    }

    /** Configure a SingleNodeContainer.
     *
     * @param snc node to be configured
     * @param keepNodeMessage Whether to keep previously set node messages
     *        (important during load sometimes)
     * @return true if the configuration did change something.
     */
    private boolean configureSingleNodeContainer(final SingleNodeContainer snc, final boolean keepNodeMessage) {
        boolean configurationChanged = false;
        synchronized (m_workflowMutex) {
            NodeMessage oldMessage = snc.getNodeMessage();
            final int inCount = snc.getNrInPorts();
            NodeID sncID = snc.getID();
            NodeOutPort[] predPorts = assemblePredecessorOutPorts(sncID);
            final PortObjectSpec[] inSpecs = new PortObjectSpec[inCount];
            final FlowObjectStack[] sos = new FlowObjectStack[inCount];
            final HiLiteHandler[] hiliteHdls = new HiLiteHandler[inCount];
            // check for presence of input specs and collects inport
            // TableSpecs, FlowObjectStacks and HiLiteHandlers
            boolean allSpecsExists = true;
            for (int i = 0; i < predPorts.length; i++) {
                if (predPorts[i] != null) {
                    inSpecs[i] = predPorts[i].getPortObjectSpec();
                    sos[i] = predPorts[i].getFlowObjectStack();
                    hiliteHdls[i] = predPorts[i].getHiLiteHandler();
                    allSpecsExists &= inSpecs[i] != null;
                } else if (snc.getInPort(i).getPortType().isOptional()) {
                    // optional input, which is not connected ... ignore
                } else {
                    allSpecsExists = false;
                }
            }
            if (!allSpecsExists) {
                // only configure nodes with all Input Specs present
                // (NodeMessage did not change -- can exit here)
                return false;
            }
            if (!canConfigureNodes()) {
                if (!keepNodeMessage) {
                    snc.setNodeMessage(new NodeMessage(NodeMessage.Type.WARNING,
                        "Outer workflow does not have input data, execute it first"));
                }
                return false;
            }

            // configure node only if it's not yet running, queued or done.
            // This can happen if the WFM queues a node which has more than
            // one predecessor with populated output ports but one of the
            // nodes still has not called the "doAfterExecution()" routine
            // which might attempt to configure an already queued node again
            switch (snc.getInternalState()) {
            case IDLE:
            case CONFIGURED:
            case UNCONFIGURED_MARKEDFOREXEC:
            case CONFIGURED_MARKEDFOREXEC:
                // nodes can be EXECUTINGREMOTELY when loaded (reconnect to a
                // grid/server) -- also these nodes will be configured() on load
            case EXECUTINGREMOTELY:
                // the stack that previously would have been propagated,
                // used to track changes
                FlowObjectStack oldFOS = snc.createOutFlowObjectStack();
                // create new FlowObjectStack
                FlowObjectStack scsc;
                FlowObjectStack nodeOutgoingStack = new FlowObjectStack(sncID);
                boolean flowStackConflict = false;
                if (isSourceNode(sncID)) {
                    // no input ports - create new stack, prefilled with Workflow variables:
                    scsc = new FlowObjectStack(sncID, getWorkflowVariableStack());
                } else {
                    try {
                        scsc = new FlowObjectStack(sncID, sos);
                    } catch (IllegalFlowObjectStackException e) {
                        LOGGER.warn("Unable to merge flow object stacks: " + e.getMessage(), e);
                        scsc = new FlowObjectStack(sncID);
                        flowStackConflict = true;
                    }
                }
                if (snc.isModelCompatibleTo(ScopeStartNode.class)) {
                    // the stack will automatically add the ID of the head of the scope (the owner!)
                    FlowScopeContext ssc = ((NativeNodeContainer)snc).getNode().getInitialScopeContext();
                    nodeOutgoingStack.push(ssc);
                }
                snc.setFlowObjectStack(scsc, nodeOutgoingStack);
                snc.setCredentialsStore(m_credentialsStore);
                // update backwards reference for loops
                if (snc.isModelCompatibleTo(LoopEndNode.class)) {
                    // if this is an END to a loop, make sure it knows its head
                    // (for both: active and inactive loops)
                    Node sncNode = ((NativeNodeContainer)snc).getNode();
                    FlowLoopContext slc = scsc.peek(FlowLoopContext.class);
                    if (slc == null) {
                        // no head found - ignore during configure!
                        sncNode.setLoopStartNode(null);
                    } else {
                        // loop seems to be correctly wired - set head
                        NodeContainer headNode = m_workflow.getNode(slc.getOwner());
                        if (headNode == null) {
                            // odd: head is not in the same workflow,
                            // ignore as well during configure
                            sncNode.setLoopStartNode(null);
                        } else {
                            // head found, let the end node know about it:
                            sncNode.setLoopStartNode(((NativeNodeContainer)headNode).getNode());
                        }
                    }
                }
                // update HiLiteHandlers on inports of SNC only
                // TODO think about it... happens magically
                for (int i = 0; i < inCount; i++) {
                    snc.setInHiLiteHandler(i, hiliteHdls[i]);
                }
                // remember HiLiteHandler on OUTPORTS of all nodes!
                HiLiteHandler[] oldHdl = new HiLiteHandler[snc.getNrOutPorts()];
                for (int i = 0; i < oldHdl.length; i++) {
                    oldHdl[i] = snc.getOutPort(i).getHiLiteHandler();
                }
                // configure node itself
                boolean outputSpecsChanged = false;
                if (flowStackConflict) {
                    // can't be configured due to stack clash.
                    // make sure execution from here on is canceled
                    disableNodeForExecution(sncID);
                    // and reset node if it's not reset already
                    // (ought to be red with this type of error!)
                    if (!snc.getInternalState().equals(InternalNodeContainerState.IDLE)) {
                        // if not already idle make sure it is!
                        snc.reset();
                    }
                    // report the problem
                    snc.setNodeMessage(new NodeMessage(Type.ERROR,
                            "Can't merge FlowVariable Stacks! (likely a loop problem.)"));
                    // different outputs - empty ports!
                    outputSpecsChanged = true;
                } else {
                    outputSpecsChanged = snc.configure(inSpecs);
                }
                // NOTE:
                // no need to clean stacks of LoopEnd nodes - done automagically
                // inside the getFlowObjectStack of the ports of LoopEnd
                // Nodes.

                // check if FlowObjectStacks have changed
                boolean stackChanged = false;
                FlowObjectStack newFOS = snc.createOutFlowObjectStack();
                stackChanged = !newFOS.equals(oldFOS);
                // check if HiLiteHandlers have changed
                boolean hiLiteHdlsChanged = false;
                for (int i = 0; i < oldHdl.length; i++) {
                    HiLiteHandler hdl = snc.getOutPort(i).getHiLiteHandler();
                    hiLiteHdlsChanged |= (hdl != oldHdl[i]);
                }
                configurationChanged = (outputSpecsChanged || stackChanged || hiLiteHdlsChanged);
                // and finally check if we can queue this node!
                if (snc.getInternalState().equals(InternalNodeContainerState.UNCONFIGURED_MARKEDFOREXEC)
                        || snc.getInternalState().equals(InternalNodeContainerState.CONFIGURED_MARKEDFOREXEC)) {
                    queueIfQueuable(snc);
                }
                break;
            case EXECUTED:
            case EXECUTED_MARKEDFOREXEC:
                // should not happen but could if reset has worked on slightly
                // different nodes than configure, for instance.
// FIXME: report errors again, once configure follows only ports, not nodes.
                LOGGER.debug("configure found " + snc.getInternalState() + " node: " + snc.getNameWithID());
                break;
            case PREEXECUTE:
            case POSTEXECUTE:
            case EXECUTING:
                // should not happen but could if reset has worked on slightly
                // different nodes than configure, for instance.
                LOGGER.debug("configure found " + snc.getInternalState() + " node: " + snc.getNameWithID());
                break;
            case CONFIGURED_QUEUED:
            case EXECUTED_QUEUED:
                // should not happen but could if reset has worked on slightly
                // different nodes than configure, for instance.
                LOGGER.debug("configure found " + snc.getInternalState() + " node: " + snc.getNameWithID());
                break;
            default:
                LOGGER.error("configure found weird state (" + snc.getInternalState() + "): " + snc.getNameWithID());
            }
            if (keepNodeMessage) {
                NodeMessage newMessage = snc.getNodeMessage();
                if (!oldMessage.equals(newMessage))  {
                    newMessage = NodeMessage.merge(oldMessage, newMessage);
                    snc.setNodeMessage(newMessage);
                }
            }
        }
        return configurationChanged;
        // we have a problem here. Subsequent metanodes with through connections
        // need to be configured no matter what - they can change their state
        // because 3 nodes before in the pipeline the execute state changed...
//        return configurationChanged == configurationChanged;
    }

    /** Configure the nodes in WorkflowManager, connected to a specific set of ports.
     * If ports == null, configure all nodes.
     * Note that this routine does NOT configure any nodes connected in
     * the parent WFM.
     *
     * @param wfm the WorkflowManager
     * @param inportIndeces indeces of incoming ports (or null if not known)
     */
    private void configureNodesConnectedToPortInWFM(final Set<Integer> inportIndeces) {
        synchronized (m_workflowMutex) {
            ArrayList<NodeAndInports> nodes = m_workflow.findAllConnectedNodes(inportIndeces);
            for (NodeAndInports nai : nodes) {
                NodeContainer nc = m_workflow.getNode(nai.getID());
                assert nc != null;
                if (nc instanceof SingleNodeContainer) {
                    configureSingleNodeContainer((SingleNodeContainer)nc, false);
                } else {
                    ((WorkflowManager)nc).configureNodesConnectedToPortInWFM(nai.getInports());
                }
            }
            // and finalize state
            // (note that we must call this even if no node was configured since a predecessor
            // and a THROUGH_Connetion can affect that state of this node, too.)
            checkForNodeStateChanges(true);
            // TODO: clean up flow object stack after we leave WFM?
        }
    }

    /**
     * Configure node and, if this node's output specs have changed
     * also configure its successors.
     *
     * @param nodeId of node to configure
     * @param configureMyself true if the node itself is to be configured
     */
    void configureNodeAndSuccessors(final NodeID nodeId, final boolean configureMyself) {
        configureNodeAndPortSuccessors(nodeId, null, configureMyself, true);
    }

    /**
     * Configure node (depending on flag) and successors of specific ports
     * of that node.
     *
     * @param id of node to configure
     * @param ports indices of output port successors are connected to (null if
     *   all are to be used).
     * @param configureMyself true if the node itself is to be configured
     * @param configurePartent true also the parent is configured (if affected)
     */
    private void configureNodeAndPortSuccessors(final NodeID nodeId,
            final Set<Integer> ports,
            final boolean configureMyself,
            final boolean configureParent) {
        // ensure we always configured ALL successors if we configure the node
        assert (!configureMyself) || (ports == null);
        // create list of properly ordered nodes (each one appears only once!)
        LinkedHashMap<NodeID, Set<Integer>> nodes;
        if (ports != null) {
            assert !configureMyself;
            // only consider nodes attached to port:
            nodes = m_workflow.getBreadthFirstListOfPortSuccessors(nodeId, ports, false);
        } else {
            // take all nodes
            nodes = m_workflow.getBreadthFirstListOfNodeAndSuccessors(nodeId, false);
        }
        // remember which ones we did configure to avoid useless configurations
        // (this list does not contain nodes where configure() didn't change
        // the specs/handlers/stacks.
        HashSet<NodeID> freshlyConfiguredNodes = new HashSet<NodeID>();
        // if not desired, don't configure origin
        if (!configureMyself) {
            // don't configure origin...
            nodes.remove(nodeId);
            // ...but pretend we did configure it
            freshlyConfiguredNodes.add(nodeId);
        }
        // Don't configure WFM itself but make sure the nodes connected
        // to this WFM are configured when we are done here...
        boolean wfmIsPartOfList = nodes.containsKey(this.getID());
        if (wfmIsPartOfList) {
            nodes.remove(this.getID());
        }
        // now iterate over the remaining nodes
        for (NodeID currNode : nodes.keySet()) {
            boolean needsConfiguration = currNode.equals(nodeId);
            for (ConnectionContainer cc : m_workflow.getConnectionsByDest(currNode)) {
                if (freshlyConfiguredNodes.contains(cc.getSource())) {
                    needsConfiguration = true;
                }
            }
            if (!needsConfiguration) {
                continue;
            }
            final NodeContainer nc = getNodeContainer(currNode);
            synchronized (m_workflowMutex) {
                if (nc instanceof SingleNodeContainer) {
                    if (configureSingleNodeContainer((SingleNodeContainer)nc, /*keepNodeMessage=*/false)) {
                        freshlyConfiguredNodes.add(nc.getID());
                    }
                } else {
                    assert nc instanceof WorkflowManager;
                    ((WorkflowManager)nc).configureNodesConnectedToPortInWFM(null);
                    freshlyConfiguredNodes.add(nc.getID());
                }
            }
        }
        // make sure internal status changes are properly reflected
        checkForNodeStateChanges(true);
        // And finally clean up: if the WFM was part of the list of nodes
        // make sure we only configure nodes actually connected to ports
        // which are connected to nodes which we did configure!
        if (configureParent && wfmIsPartOfList) {
            Set<Integer> portsToConf = new LinkedHashSet<Integer>();
            for (int i = 0; i < getNrWorkflowOutgoingPorts(); i++) {
                for (ConnectionContainer cc : m_workflow.getConnectionsByDest(
                        this.getID())) {
                    assert cc.getType().isLeavingWorkflow();
                    if ((cc.getDestPort() == i)
                        && (freshlyConfiguredNodes.contains(cc.getSource()))) {
                        portsToConf.add(i);
                    }
                }
            }
            if (!portsToConf.isEmpty()) {
                getParent().configureNodeAndPortSuccessors(this.getID(), portsToConf, false, configureParent);
            }
        }
    }

    /**
     * Fill array holding all input specs for the given node.
     *
     * @param id of node
     * @param inSpecs return array for specs of all predecessors
     */
    void assembleInputSpecs(final NodeID id,
            final PortObjectSpec[] inSpecs) {
        NodeOutPort[] ports = assemblePredecessorOutPorts(id);
        assert inSpecs.length == ports.length;
        for (int i = 0; i < inSpecs.length; i++) {
            if (ports[i] != null) {
                inSpecs[i] = ports[i].getPortObjectSpec();
            } else {
                inSpecs[i] = null;
            }
        }
    }

    /** Fill array holding input data for a given node.
     * @param id the node
     * @param inData An empty array being filled by this method
     * @return If all data is available (all non-optional ports are connected
     * and have data)
     */
    boolean assembleInputData(final NodeID id, final PortObject[] inData) {
        NodeContainer nc = getNodeContainer(id);
        NodeOutPort[] ports = assemblePredecessorOutPorts(id);
        assert ports.length == inData.length;
        boolean allDataAvailable = true;
        for (int i = 0; i < ports.length; i++) {
            if (ports[i] != null) {
                inData[i] = ports[i].getPortObject();
                allDataAvailable &= inData[i] != null;
            } else if (nc.getInPort(i).getPortType().isOptional()) {
                // unconnected optional input - ignore
            } else {
                allDataAvailable = false;
            }
        }
        return allDataAvailable;
    }

    /** Produce summary of node.
     *
     * @param prefix if containing node/workflow
     * @param indent number of leading spaces
     * @return string
     */
    public String printNodeSummary(final NodeID prefix, final int indent) {
        char[] indentChars = new char[indent];
        Arrays.fill(indentChars, ' ');
        String indentString = new String(indentChars);
        StringBuilder build = new StringBuilder(indentString);
        synchronized (m_workflowMutex) {
            build.append(getNameWithID());
            build.append(": " + getInternalState() + " (start)\n");
            for (NodeID id : m_workflow.getNodeIDs()) {
                if (id.hasPrefix(prefix)) {
                    NodeContainer nc = m_workflow.getNode(id);
                    if (nc instanceof WorkflowManager) {
                        build.append(((WorkflowManager)nc).printNodeSummary(
                                nc.getID(), indent + 2));
                    } else {
                        build.append(indentString);
                        build.append("  ");
                        build.append(nc.toString());
                        if (nc.isDirty()) {
                            build.append("*");
                        }
                        build.append("\n");
                    }
                } else {    // skip remaining nodes with wrong prefix
                    break;
                }
            }
            build.append(indentString);
            build.append(getNameWithID());
            build.append("(end)\n");
        }
        return build.toString();
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "(WFM) " + super.toString();
    }

    ////////////////////////
    // WFM functionality
    ////////////////////////

    /** {@inheritDoc} */
    @Override
    public Collection<NodeContainer> getNodeContainers() {
        return Collections.unmodifiableCollection(m_workflow.getNodeValues());
    }

    /**
     * @return collection of ConnectionContainer in this WFM
     */
    public Collection<ConnectionContainer> getConnectionContainers() {
        Set<ConnectionContainer> result =
            new LinkedHashSet<ConnectionContainer>();
        for (Set<ConnectionContainer> s
                : m_workflow.getConnectionsBySourceValues()) {
            if (s != null) {
                result.addAll(s);
            }
        }
        return result;
    }

    /**
     * @param id node ID
     * @return NodeContainer for given ID
     */
    public NodeContainer getNodeContainer(final NodeID id) {
        NodeContainer nc = m_workflow.getNode(id);
        if (nc == null) {
            throw new IllegalArgumentException("No such node ID: " + id);
        }
        return nc;
    }

    /** Get contained node container and cast to argument class. Throws exception if it not exists or not implementing
     * requested class unless the flag is false.
     * @param <T> The interface or subclass the {@link NodeContainer} is expected to implement.
     * @param id The node to retrieve.
     * @param subclass the expected sub class, usually sub-classes of {@link NodeContainer} but could also be
     *        implementing interface.
     * @param failOnError Fails if node is not found or not of expected type. Otherwise it just prints a DEBUG message.
     * @return The node..
     * @throws IllegalArgumentException If node is not found or of the expected type and the flag is true.
     * @since 2.10
     * @noreference This method is not intended to be referenced by clients (only used in core and testing plugin). */
    public <T> T getNodeContainer(final NodeID id, final Class<T> subclass, final boolean failOnError) {
        NodeContainer nc = m_workflow.getNode(id);
        if (nc == null || !subclass.isInstance(nc)) {
            String message = nc == null ? "Invalid node ID \"" + id + "\"" : String.format(
                "Node with ID \"%s\" exists but it's not implementing the requested class %s (is a %s)",
                id, subclass.getSimpleName(), nc.getClass().getName());
            if (failOnError) {
                throw new IllegalArgumentException(message);
            } else {
                LOGGER.debug(message);
                return null;
            }
        }
        return subclass.cast(nc);
    }

    /** Does the workflow contain a node with the argument id?
     * @param id The id in question.
     * @return true if there is node with the given id, false otherwise.
     */
    public boolean containsNodeContainer(final NodeID id) {
        return m_workflow.getNode(id) != null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean containsExecutedNode() {
        synchronized (m_workflowMutex) {
            for (NodeContainer nc : m_workflow.getNodeValues()) {
                if (nc instanceof WorkflowManager) {
                    if (((WorkflowManager)nc).containsExecutedNode()) {
                        return true;
                    }
                } else if (nc.getInternalState().equals(InternalNodeContainerState.EXECUTED)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * @return list of errors messages (list empty if none exist).
     * @deprecated Use {@link #getNodeMessages(Type...)} instead.
     */
    @Deprecated
    public List<NodeMessage> getNodeErrorMessages() {
        List<NodeMessage> result = new ArrayList<>();
        for (Pair<String, NodeMessage> p : getNodeMessages(NodeMessage.Type.ERROR)) {
            result.add(p.getSecond());
        }
        return result;
    }

    /** Get all node messages, recursively collected from all contained.
     * @param types A list of messge types (e.g. all errors and warnings). Argument must not be empty,
     *        null or contain null values
     * @return list of errors messages (list empty if none exist).
     * @throws IllegalArgumentException If argument is invalid.
     * @since 2.11
     */
    public List<Pair<String, NodeMessage>> getNodeMessages(final NodeMessage.Type... types) {
        CheckUtils.checkArgumentNotNull(types, "Argument must not be null");
        final List<Type> asList = Arrays.asList(types);
        CheckUtils.checkArgument(asList.size() > 0 && !asList.contains(null),
            "Type list must not be empty, nor contain null");
        synchronized (m_workflowMutex) {
            ArrayList<Pair<String, NodeMessage>> result = new ArrayList<>();
            for (NodeContainer nc : m_workflow.getNodeValues()) {
                if (nc instanceof NativeNodeContainer) {
                    if (asList.contains(nc.getNodeMessage().getMessageType())) {
                        result.add(Pair.create(nc.getNameWithID(), nc.getNodeMessage()));
                    }
                } else if (nc instanceof SubNodeContainer) {
                    List<Pair<String, NodeMessage>> subResult =
                            ((SubNodeContainer)nc).getWorkflowManager().getNodeMessages(types);
                    result.addAll(subResult);
                } else {
                    assert nc instanceof WorkflowManager;
                    List<Pair<String, NodeMessage>> subResult = ((WorkflowManager)nc).getNodeMessages(types);
                    result.addAll(subResult);
                }
            }
            return result;
        }
    }

    /** Return list of nodes that are part of the same scope as the given one.
     * List will contain anchor node alone if there is no scope around it.
     *
     * @param anchor node
     * @return list of nodes.
     * @since 2.8
     */
    public List<NodeContainer> getNodesInScope(final SingleNodeContainer anchor) {
        List<NodeContainer> result = m_workflow.getNodesInScope(anchor);
        return result;
    }

    /**
     * @param anchor The anchor
     * @return List of node containers
     * @since 2.8
     */
    public List<NodeContainer> getNodesInScopeOLD(final SingleNodeContainer anchor) {
        ArrayList<NodeContainer> result = new ArrayList<NodeContainer>();
        // get closest (=top of stack) scope context for given anchor
        FlowScopeContext anchorFSC = anchor.getFlowObjectStack().peek(FlowScopeContext.class);
        if (anchor.isModelCompatibleTo(ScopeStartNode.class)) {
            anchorFSC = anchor.getOutgoingFlowObjectStack().peek(FlowScopeContext.class);
        }
        if (anchorFSC == null) {
            // not in a loop: bail!
            result.add(anchor);
            return result;
        }
        // check for all nodes in workflow if they have the same scope context object on their stack:
        NodeID anchorOwner = anchorFSC.getOwner();
        for (NodeContainer nc : m_workflow.getNodeValues()) {
            if (anchorOwner.equals(nc.getID())) {
                result.add(nc);
            } else {
                if (nc instanceof SingleNodeContainer) {
                    FlowObjectStack fsc = ((SingleNodeContainer)nc).getFlowObjectStack();
                    List<FlowObject> os = fsc.getFlowObjectsOwnedBy(anchorFSC.getOwner());
                    if (os.contains(anchorFSC)) {
                        result.add(nc);
                    }
                } else {
                    // WorkflowManager: we need to check the outgoing ports individually:
                    if (nc instanceof WorkflowManager) {
                        boolean isIn = false;
                        WorkflowManager wfm = (WorkflowManager)nc;
                        for (int i = 0; i < wfm.getNrOutPorts(); i++) {
                            FlowObjectStack fos = wfm.getOutPort(i).getFlowObjectStack();
                            if (fos != null) {
                                // only check if outport is (internally) connected
                                List<FlowObject> os = fos.getFlowObjectsOwnedBy(anchorFSC.getOwner());
                                if (os.contains(anchorFSC)) {
                                    isIn = true;
                                    break;
                                }
                            }
                        }
                        if (!isIn) {
                            // check inports, too, in case an inport connected to the loop ends inside the metanode:
                            for (int i = 0; i < wfm.getNrInPorts(); i++) {
                                FlowObjectStack fos = wfm.getInPort(i).getUnderlyingPort().getFlowObjectStack();
                                if (fos != null) {
                                    // only check if inport is actually connected
                                    List<FlowObject> os = fos.getFlowObjectsOwnedBy(anchorFSC.getOwner());
                                    if (os.contains(anchorFSC)) {
                                        isIn = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (isIn) {
                            result.add(nc);
                        }
                    }
                }
            }
        }
        return result;
    }

    ////////////////////////
    // WFM template handling
    ////////////////////////

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isWriteProtected() {
        if (this == ROOT) {
            return false;
        }
        synchronized (m_workflowMutex) {
            return getDirectNCParent().isWriteProtected()
                || m_isWorkflowDirectoryReadonly
                || Role.Link.equals(getTemplateInformation().getRole());
        }
    }

    /** @return the templateInformation */
    @Override
    public MetaNodeTemplateInformation getTemplateInformation() {
        return m_templateInformation;
    }

    /** {@inheritDoc} */
    @Override
    public void setTemplateInformation(final MetaNodeTemplateInformation tI) {
        CheckUtils.checkArgumentNotNull(tI, "Argument must not be null.");
        CheckUtils.checkArgument(!Role.Template.equals(tI.getRole())
            || TemplateType.MetaNode.equals(tI.getNodeContainerTemplateType()),
            "Template type expected to be metanode: %s", tI.getNodeContainerTemplateType());
        m_templateInformation = tI;
        notifyNodePropertyChangedListener(NodeProperty.TemplateConnection);
        setDirty();
    }

    /** The list of contained meta nodes that are linked meta nodes. If recurse flag is set, each meta node is checked
     * recursively.
     * @param recurse ...
     * @return list of node ids, ids not necessarily direct childs of this WFM!
     * @since 2.6
     */
    public List<NodeID> getLinkedMetaNodes(final boolean recurse) {
        synchronized (m_workflowMutex) {
            Map<NodeID, NodeContainerTemplate> filled =
                    fillLinkedTemplateNodesList(new LinkedHashMap<NodeID, NodeContainerTemplate>(), recurse, false);
            return new ArrayList<NodeID>(filled.keySet());
        }
    }

    /** {@inheritDoc}
     * @noreference This method is not intended to be referenced by clients. */
    @Override
    public Map<NodeID, NodeContainerTemplate> fillLinkedTemplateNodesList(
        final Map<NodeID, NodeContainerTemplate> mapToFill, final boolean recurse,
        final boolean stopRecursionAtLinkedMetaNodes) {
        for (NodeID id : m_workflow.createBreadthFirstSortedList(m_workflow.getNodeIDs(), true).keySet()) {
            NodeContainer nc = getNodeContainer(id);
            if (!(nc instanceof NodeContainerTemplate)) {
                continue;
            }
            NodeContainerTemplate tnc = (NodeContainerTemplate) nc;
            if (tnc.getTemplateInformation().getRole().equals(Role.Link)) {
                mapToFill.put(tnc.getID(), tnc);
                if (stopRecursionAtLinkedMetaNodes) {
                    continue;
                }
            }
            if (recurse) {
                tnc.fillLinkedTemplateNodesList(mapToFill, true, stopRecursionAtLinkedMetaNodes);
            }
        }
        return mapToFill;
    }

    /**
     * Query the template to the linked meta node with the given ID and check whether a newer version is available.
     *
     * @param id The ID of the linked meta node
     * @param loadHelper The load helper to load the template
     * @return true if a newer revision is available, false if not or this is not a meta node link.
     * @throws IOException If that fails (template not accessible)
     */
    public boolean checkUpdateMetaNodeLink(final NodeID id, final WorkflowLoadHelper loadHelper) throws IOException {
        final HashMap<URI, NodeContainerTemplate> visitedTemplateMap = new HashMap<URI, NodeContainerTemplate>();
        try {
            final LoadResult loadResult = new LoadResult("ignored");
            boolean result = checkUpdateMetaNodeLinkWithCache(id, loadHelper, loadResult, visitedTemplateMap, true);
            if (loadResult.hasErrors()) {
                throw new IOException("Errors checking updates:\n"
                    + loadResult.getFilteredError("  ", LoadResultEntryType.Error));
            }
            return result;
        } finally {
            for (NodeContainerTemplate tempLink : visitedTemplateMap.values()) {
                tempLink.getParent().removeNode(tempLink.getID());
            }
        }
    }

    /** Implementation of #checkUpdateMetaNodeLink that uses a cache of already checked meta node links.
     * @param loadResult Errors while loading the template are added here
     * @param visitedTemplateMap avoids repeated checks for copies of the same meta node link.
     * @param recurseInto Should linked meta nodes contained in the meta node also be checked. */
    private boolean checkUpdateMetaNodeLinkWithCache(final NodeID id, final WorkflowLoadHelper loadHelper,
        final LoadResult loadResult, final Map<URI, NodeContainerTemplate> visitedTemplateMap,
        final boolean recurseInto) throws IOException {
        NodeContainer nc = m_workflow.getNode(id);
        if (!(nc instanceof NodeContainerTemplate)) {
            return false;
        }
        NodeContainerTemplate tnc = (NodeContainerTemplate)nc;
        Map<NodeID, NodeContainerTemplate> idsToCheck = new LinkedHashMap<NodeID, NodeContainerTemplate>();
        if (tnc.getTemplateInformation().getRole().equals(Role.Link)) {
            idsToCheck.put(id, tnc);
        }
        if (recurseInto) {
            idsToCheck = tnc.fillLinkedTemplateNodesList(idsToCheck, true, false);
        }
        boolean hasUpdate = false;
        for (NodeContainerTemplate linkedMeta : idsToCheck.values()) {
            MetaNodeTemplateInformation linkInfo = linkedMeta.getTemplateInformation();
            final URI uri = linkInfo.getSourceURI();
            NodeContainerTemplate tempLink = visitedTemplateMap.get(uri);
            if (tempLink == null) {
                try {
                    final LoadResult templateLoadResult = new LoadResult("Template to " + uri);
                    tempLink = loadMetaNodeTemplate(linkedMeta, loadHelper, templateLoadResult);
                    loadResult.addChildError(templateLoadResult);
                    visitedTemplateMap.put(uri, tempLink);
                } catch (Exception e) {
                    if (linkInfo.setUpdateStatusInternal(UpdateStatus.Error)) {
                        linkedMeta.notifyTemplateConnectionChangedListener();
                    }
                    if (e instanceof IOException) {
                        throw (IOException)e;
                    } else if (e instanceof CanceledExecutionException) {
                        throw new IOException("Canceled while loading from template", e);
                    } else if (e instanceof RuntimeException) {
                        throw (RuntimeException)e;
                    } else {
                        throw new RuntimeException(e);
                    }
                }
            }
            boolean hasThisOneAnUpdate = tempLink.getTemplateInformation().isNewerThan(linkInfo);
            UpdateStatus updateStatus = hasThisOneAnUpdate ? UpdateStatus.HasUpdate : UpdateStatus.UpToDate;
            hasUpdate = hasUpdate || hasThisOneAnUpdate;
            if (linkInfo.setUpdateStatusInternal(updateStatus)) {
                linkedMeta.notifyTemplateConnectionChangedListener();
            }
        }
        return hasUpdate;
    }

    /** Reads the template info from the metanode argument and then resolves that URI and returns a workflow manager
     * that lives as child of {@link #templateWorkflowRoot}. Used to avoid duplicate loading from a remote location. The
     * returned instance is then copied to the final destination. */
    private NodeContainerTemplate loadMetaNodeTemplate(final NodeContainerTemplate meta,
        final WorkflowLoadHelper loadHelper, final LoadResult loadResult)
                throws IOException, UnsupportedWorkflowVersionException, CanceledExecutionException {
        MetaNodeTemplateInformation linkInfo = meta.getTemplateInformation();
        URI sourceURI = linkInfo.getSourceURI();
        WorkflowManager tempParent = lazyInitTemplateWorkflowRoot();
        MetaNodeLinkUpdateResult loadResultChild;
        NodeContext.pushContext((NodeContainer)meta);
        try {
            File localDir = ResolverUtil.resolveURItoLocalOrTempFile(sourceURI);
            TemplateNodeContainerPersistor loadPersistor = loadHelper.createTemplateLoadPersistor(localDir, sourceURI);
            loadResultChild = new MetaNodeLinkUpdateResult("Template from " + sourceURI.toString());
            tempParent.load(loadPersistor, loadResultChild, new ExecutionMonitor(), false);
        } catch (InvalidSettingsException e) {
            throw new IOException("Unable to read template meta node: " + e.getMessage(), e);
        } finally {
            NodeContext.removeLastContext();
        }
        NodeContainerTemplate linkResult = loadResultChild.getLoadedInstance();
        MetaNodeTemplateInformation templInfo = linkResult.getTemplateInformation();
        Role sourceRole = templInfo.getRole();
        switch (sourceRole) {
        case Link:
            // "Template" field in the workflow settings changed to "Link" during preLoadNodeContainer
            // (this is due to the template information link uri set above)
            break;
        default:
            throw new IOException("The source of the linked instance does "
                    + "not represent a template but is of role " + sourceRole);
        }
        loadResult.addChildError(loadResultChild);
        return linkResult;
    }

    /** Returns true if the argument node is a valid meta node link and is not
     * executing and has no successor in execution. Used from the GUI to enable
     * or disable the update action. It does not test whether there is a newer
     * version of the meta node available. It may also return true even if the
     * meta node is executed or contains executed nodes.
     * @param id The meta node in question.
     * @return The above described property. */
    public boolean canUpdateMetaNodeLink(final NodeID id) {
        synchronized (m_workflowMutex) {
            NodeContainer nc = m_workflow.getNode(id);
            if (!(nc instanceof NodeContainerTemplate)) {
                return false;
            }
            NodeContainerTemplate tnc = (NodeContainerTemplate)nc;
            switch (tnc.getTemplateInformation().getRole()) {
            case Link:
                break;
            default:
                return false;
            }
            return !(nc.getInternalState().isExecutionInProgress() || hasSuccessorInProgress(id));
        }
    }

    /** Returns true when the meta node for the given ID is a link or contains links, which have the update status set
     * (doesn't actually check on remote side but uses cached information). It assumes
     * {@link #checkUpdateMetaNodeLink(NodeID, WorkflowLoadHelper)} has been called before.
     *
     * <p>This method is used by an UI action and is not meant as public API.
     * @param id The id to the meta node.
     * @return if the ID is unknown or there are no meta nodes with the appropriate update flag.
     * @since 2.9
     */
    public boolean hasUpdateableMetaNodeLink(final NodeID id) {
        synchronized (m_workflowMutex) {
            NodeContainer nc = m_workflow.getNode(id);
            if (!(nc instanceof NodeContainerTemplate)) {
                return false;
            }
            NodeContainerTemplate tnc = (NodeContainerTemplate)nc;
            final MetaNodeTemplateInformation templInfo = tnc.getTemplateInformation();
            if (templInfo.getRole().equals(Role.Link) && templInfo.getUpdateStatus().equals(UpdateStatus.HasUpdate)) {
                return true;
            }
            final LinkedHashMap<NodeID, NodeContainerTemplate> linkedMNToCheck =
                new LinkedHashMap<NodeID, NodeContainerTemplate>();
            Map<NodeID, NodeContainerTemplate> linkedChildren =
                    tnc.fillLinkedTemplateNodesList(linkedMNToCheck, true, false);
            for (NodeContainerTemplate tempNc : linkedChildren.values()) {
                if (tempNc.getTemplateInformation().getUpdateStatus().equals(UpdateStatus.HasUpdate)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Update link meta nodes with the given ID.
     *
     * @param id The ids of the meta node (must be existing meta node and must be a link).
     * @param exec The execution monitor used to load a copy of the template
     * @param loadHelper A load helper.
     * @return The load result of the newly inserted link copy.
     * @throws CanceledExecutionException If canceled
     * @throws IllegalArgumentException If the node does not exist or is not a meta node.
     */
    public NodeContainerTemplateLinkUpdateResult updateMetaNodeLink(final NodeID id, final ExecutionMonitor exec,
        final WorkflowLoadHelper loadHelper) throws CanceledExecutionException {
        final HashMap<URI, NodeContainerTemplate> visitedTemplateMap = new HashMap<URI, NodeContainerTemplate>();
        try {
            return updateMetaNodeLinkWithCache(id, exec, loadHelper, visitedTemplateMap);
        } finally {
            for (NodeContainerTemplate tempLink : visitedTemplateMap.values()) {
                tempLink.getParent().removeNode(tempLink.getID());
            }
        }
    }

    /**
     * Implementation of {@link #updateMetaNodeLink(NodeID, ExecutionMonitor, WorkflowLoadHelper)} with an extra
     * cache argument.
     * @param visitedTemplateMap The map to avoid loading duplicate
     */
    private NodeContainerTemplateLinkUpdateResult updateMetaNodeLinkWithCache(final NodeID id, final ExecutionMonitor exec,
        final WorkflowLoadHelper loadHelper, final Map<URI, NodeContainerTemplate> visitedTemplateMap)
                throws CanceledExecutionException {
        final NodeContainerTemplateLinkUpdateResult loadRes =
                new NodeContainerTemplateLinkUpdateResult("Update node link \"" + getNameWithID() + "\"");
        NodeContainer nc = getNodeContainer(id);
        if (!(nc instanceof NodeContainerTemplate)) {
            throw new IllegalArgumentException("Node \"" + nc.getNameWithID()
                + "\" is not a template node (can't update link)");
        }
        NodeContainerTemplate tnc = (NodeContainerTemplate)nc;
        final MetaNodeTemplateInformation tempInfo = tnc.getTemplateInformation();
        if (!tempInfo.getRole().equals(Role.Link)) {
            loadRes.addError("Node \"" + getNameWithID() + "\" is not a template node link");
            return loadRes;
        }
        synchronized (m_workflowMutex) {
            boolean needsUpdate;
            try {
                needsUpdate = checkUpdateMetaNodeLinkWithCache(id, loadHelper, loadRes, visitedTemplateMap, false);
            } catch (IOException e2) {
                String msg = "Unable to check node update for " + tnc.getNameWithID() + ": " + e2.getMessage();
                loadRes.addError(msg);
                return loadRes;
            }
            NodeSettings ncSettings = null;
            if (needsUpdate) { // don't need to apply old settings as the meta node link is still up-to-date
                ncSettings = new NodeSettings("metanode_settings");
                try {
                    saveNodeSettings(id, ncSettings);
                } catch (InvalidSettingsException e1) {
                    String error = "Unable to store meta node settings: " + e1.getMessage();
                    LOGGER.warn(error, e1);
                    loadRes.addError(error);
                }
            }
            WorkflowCopyContent oldContent = new WorkflowCopyContent();
            oldContent.setNodeIDs(id);
            oldContent.setIncludeInOutConnections(true);
            WorkflowPersistor copy = copy(true, oldContent);
            NodeContainerTemplate updatedTemplate = null;
            try {
                NodeContainerTemplate recursionManager;
                if (needsUpdate) {
                    updatedTemplate = updateNodeTemplateLinkInternal(id, exec, loadHelper, visitedTemplateMap, loadRes);
                    recursionManager = updatedTemplate;
                } else {
                    loadRes.setNCTemplate(tnc); // didn't update so this will be its own reference
                    recursionManager = tnc;
                }
                recursionManager.updateMetaNodeLinkInternalRecursively(exec, loadHelper, visitedTemplateMap, loadRes);
            } catch (Exception e) {
                e.printStackTrace();
                String error = e.getClass().getSimpleName() + " while loading template: " + e.getMessage();
                LOGGER.error(error, e);
                loadRes.addError(error);
                if (updatedTemplate != null) {
                    removeNode(updatedTemplate.getID());
                }
                paste(copy);
                return loadRes;
            }
            if (needsUpdate) {
                try {
                    loadNodeSettings(loadRes.getNCTemplate().getID(), ncSettings);
                } catch (InvalidSettingsException e) {
                    String error = "Can't apply previous settigs to new meta node link: " + e.getMessage();
                    LOGGER.warn(error, e);
                    loadRes.addError(error);
                }
            }
            loadRes.setUndoPersistor(copy);
            return loadRes;
        }
    }

    /** {@inheritDoc}
     * @noreference This method is not intended to be referenced by clients. */
    @Override
    public void updateMetaNodeLinkInternalRecursively(final ExecutionMonitor exec,
        final WorkflowLoadHelper loadHelper, final Map<URI, NodeContainerTemplate> visitedTemplateMap,
        final NodeContainerTemplateLinkUpdateResult loadRes) throws Exception {
        for (NodeID id : m_workflow.createBreadthFirstSortedList(m_workflow.getNodeIDs(), true).keySet()) {
            NodeContainer nc = getNodeContainer(id);
            if (nc instanceof NodeContainerTemplate) {
                NodeContainerTemplate t = (NodeContainerTemplate)nc;
                if (t.getTemplateInformation().getRole().equals(Role.Link)) {
                    final NodeContainerTemplateLinkUpdateResult childResult =
                            new NodeContainerTemplateLinkUpdateResult("Update child link \"" + getNameWithID() + "\"");
                    t = updateNodeTemplateLinkInternal(t.getID(), exec, loadHelper, visitedTemplateMap, childResult);
                }
                t.updateMetaNodeLinkInternalRecursively(exec, loadHelper, visitedTemplateMap, loadRes);
            }
        }
    }

    /** Actual implementation to #updateMetaNodeLink. It updates a single meta node, which must be a linked meta node.
     * It does not keep any backups and doesn't have a rollback.
     */
    private NodeContainerTemplate updateNodeTemplateLinkInternal(final NodeID id, final ExecutionMonitor exec,
        final WorkflowLoadHelper loadHelper, final Map<URI, NodeContainerTemplate> visitedTemplateMap,
        final NodeContainerTemplateLinkUpdateResult loadRes) throws Exception {
        NodeContainerTemplate tnc = (NodeContainerTemplate)m_workflow.getNode(id);
        MetaNodeTemplateInformation templInfo = tnc.getTemplateInformation();
        assert templInfo.getRole().equals(Role.Link);
        URI sourceURI = templInfo.getSourceURI();
        NodeContainerTemplate tempLink = visitedTemplateMap.get(sourceURI);
        if (tempLink == null) {
            try {
                tempLink = loadMetaNodeTemplate(tnc, loadHelper, loadRes);
                visitedTemplateMap.put(sourceURI, tempLink);
            } catch (IOException e) {
                String error = "Failed to update meta node reference: " + e.getMessage();
                LOGGER.error(error, e);
                loadRes.addError(error);
                return null;
            } catch (UnsupportedWorkflowVersionException e) {
                String error = "Unsupported version in meta node template " + e.getMessage();
                LOGGER.error(error, e);
                loadRes.addError(error);
                return null;
            }
        }
        NodeContainerTemplate newLinkMN;
        synchronized (m_workflowMutex) {
            NodeUIInformation oldUI = m_workflow.getNode(id).getUIInformation();
            NodeUIInformation newUI = oldUI != null ? oldUI.clone() : null;
            // keep old in/out connections to later relink them
            Set<ConnectionContainer> inConns = getIncomingConnectionsFor(id);
            Set<ConnectionContainer> outConns = getOutgoingConnectionsFor(id);

            removeNode(id);
            WorkflowCopyContent pasteResult = copyFromAndPasteHere(tempLink.getParent(),
                new WorkflowCopyContent().setNodeID(tempLink.getID(), id.getIndex(), newUI));
            newLinkMN = getNodeContainer(pasteResult.getNodeIDs()[0], NodeContainerTemplate.class, true);
            loadRes.setNCTemplate(newLinkMN);

            for (ConnectionContainer cc : inConns) {
                NodeID s = cc.getSource();
                int sourcePort = cc.getSourcePort();
                int destPort = cc.getDestPort();
                if (!canAddConnection(s, sourcePort, id, destPort)) {
                    loadRes.addWarning("Could not restore connection between \""
                            + getNodeContainer(s).getNameWithID() + "\" and meta node template");
                } else {
                    ConnectionContainer c = addConnection(s, sourcePort, id, destPort);
                    c.setDeletable(cc.isDeletable());
                    ConnectionUIInformation uiInfo = cc.getUIInfo();
                    c.setUIInfo(uiInfo != null ? uiInfo.clone() : null);
                }
            }
            for (ConnectionContainer cc : outConns) {
                int sourcePort = cc.getSourcePort();
                int destPort = cc.getDestPort();
                NodeID des = cc.getDest();
                if (!canAddConnection(id, sourcePort, des, destPort)) {
                    loadRes.addError("Could not restore connection between meta node template and \""
                            + getNodeContainer(des).getNameWithID() + "\"");
                } else {
                    ConnectionContainer c = addConnection(id, sourcePort, des, destPort);
                    c.setDeletable(cc.isDeletable());
                    ConnectionUIInformation uiInfo = cc.getUIInfo();
                    c.setUIInfo(uiInfo != null ? uiInfo.clone() : null);
                }
            }
            return newLinkMN;
        }
    }

    /**
     * Update meta node links (recursively finding all meta nodes but not updating meta nodes in meta nodes).
     * @param lH Load helper.
     * @param failOnLoadError If to fail if there errors updating the links
     * @param exec Progress monitor
     * @return The update summary
     * @throws CanceledExecutionException If canceled
     * @throws IOException Special errors during update (not accessible)
     * @noreference This method is not intended to be referenced by clients.
     */
    public NodeContainerTemplateLinkUpdateResult updateMetaNodeLinks(final WorkflowLoadHelper lH,
        final boolean failOnLoadError, final ExecutionMonitor exec) throws IOException, CanceledExecutionException {
        // all linked meta nodes that need to be checked.
        Map<NodeID, NodeContainerTemplate> linkedMetaNodes =
                fillLinkedTemplateNodesList(new LinkedHashMap<NodeID, NodeContainerTemplate>(), true, true);
        int linksChecked = 0;
        int linksUpdated = 0;
        NodeContainerTemplateLinkUpdateResult update = new NodeContainerTemplateLinkUpdateResult(
            "Update on " + linkedMetaNodes.size() + " node(s) in " + getNameWithID());
        HashMap<URI, NodeContainerTemplate> visitedTemplateMap = new HashMap<URI, NodeContainerTemplate>();
        try {
            for (NodeContainerTemplate tnc : linkedMetaNodes.values()) {
                linksChecked += 1;
                WorkflowManager parent = tnc.getParent();
                exec.setProgress(linksChecked / (double)linkedMetaNodes.size(), "node " + tnc.getNameWithID());
                exec.checkCanceled();
                LoadResult checkTemplateResult = new LoadResult("update check");
                final boolean updatesAvail = parent.checkUpdateMetaNodeLinkWithCache(
                    tnc.getID(), lH, checkTemplateResult, visitedTemplateMap, true);
                if (failOnLoadError && checkTemplateResult.hasErrors()) {
                    LOGGER.error(checkTemplateResult.getFilteredError("", LoadResultEntryType.Error));
                    throw new IOException("Error(s) while updating meta node links");
                }
                if (updatesAvail) {
                    NodeContainerTemplateLinkUpdateResult loadResult = parent.updateMetaNodeLinkWithCache(tnc.getID(),
                        exec.createSubProgress(1.0 / linkedMetaNodes.size()), lH, visitedTemplateMap);
                    update.addChildError(loadResult);
                    linksUpdated += 1;
                    if (failOnLoadError && loadResult.hasErrors()) {
                        LOGGER.error(loadResult.getFilteredError("", LoadResultEntryType.Error));
                        throw new IOException("Error(s) while updating meta node links");
                    }
                }
            }
            if (linksChecked == 0) {
                LOGGER.debug("No meta node links in workflow, nothing updated");
            } else {
                LOGGER.debug("Workflow contains " + linksChecked + " meta node link(s), " + linksUpdated
                    + " were updated");
            }
            return update;
        } finally {
            for (NodeContainerTemplate tempLink : visitedTemplateMap.values()) {
                tempLink.getParent().removeNode(tempLink.getID());
            }
        }
    }

    /** Root workflow to all temporary workflow templates. While updating or definining meta node links/templates
     * this instance is used to hold a temporary copy. Initialized lazy.*/
    private static WorkflowManager templateWorkflowRoot;

    /** @return lazy initialized {@link #templateWorkflowRoot}. */
    static WorkflowManager lazyInitTemplateWorkflowRoot() {
        synchronized (WorkflowManager.class) {
            if (templateWorkflowRoot == null) {
                templateWorkflowRoot = ROOT.createAndAddProject("Workflow Template Root", new WorkflowCreationHelper());
            }
            return templateWorkflowRoot;
        }
    }

    /** {@inheritDoc} */
    @Override
    public MetaNodeTemplateInformation saveAsTemplate(final File directory, final ExecutionMonitor exec)
        throws IOException, CanceledExecutionException, LockFailedException {
        WorkflowManager tempParent = lazyInitTemplateWorkflowRoot();
        WorkflowManager copy = null;
        ReferencedFile workflowDirRef = new ReferencedFile(directory);
        workflowDirRef.lock();
        try {
            WorkflowCopyContent cnt = new WorkflowCopyContent();
            cnt.setNodeIDs(getID());
            synchronized (m_workflowMutex) {
                cnt = tempParent.copyFromAndPasteHere(getParent(), cnt);
            }
            NodeID cID = cnt.getNodeIDs()[0];
            copy = (WorkflowManager)tempParent.getNodeContainer(cID);
            MetaNodeTemplateInformation template = MetaNodeTemplateInformation.createNewTemplate(WorkflowManager.class);
            synchronized (copy.m_workflowMutex) {
                copy.setTemplateInformation(template);
                copy.setName(null);
                NodeSettings templateSettings = MetaNodeTemplateInformation.createNodeSettingsForTemplate(copy);
                copy.save(directory, new WorkflowSaveHelper(true, false), exec);
                templateSettings.saveToXML(new FileOutputStream(
                    new File(workflowDirRef.getFile(), WorkflowPersistor.TEMPLATE_FILE)));
            }
            return template;
        } finally {
            if (copy != null) {
                tempParent.removeNode(copy.getID());
            }
            workflowDirRef.unlock();
        }
    }

    /** Sets the argument template info on the node with the given ID. The node
     * must be a valid meta node contained in this workflow.
     * @param id The id of the node to change (must be an existing meta node).
     * @param templateInformation the templateInformation to set
     * @return The old template info associated with the node.
     * @throws NullPointerException If either argument is null.
     * @throws IllegalArgumentException If the id does not represent a
     * valid meta node. */
    public MetaNodeTemplateInformation setTemplateInformation(final NodeID id,
            final MetaNodeTemplateInformation templateInformation) {
        synchronized (m_workflowMutex) {
            NodeContainerTemplate nc = getNodeContainer(id, NodeContainerTemplate.class, true);
            MetaNodeTemplateInformation old = nc.getTemplateInformation();
            nc.setTemplateInformation(templateInformation);
            return old;
        }
    }

    ///////////////////////////////////
    // Workflow Encryption & Locking
    ///////////////////////////////////

    /** Set password on this meta node. See {@link WorkflowCipher} for details
     * on what is protected/locked.
     * @param password The new password (or null to always unlock)
     * @param hint The hint/copyright.
     * @throws NoSuchAlgorithmException If encryption fails. */
    public void setWorkflowPassword(final String password, final String hint)
        throws NoSuchAlgorithmException {
        if (this == ROOT) {
            throw new IllegalStateException("Can't set cipher on ROOT wfm.");
        }
        WorkflowCipher cipher = WorkflowCipher.newCipher(password, hint);
        if (cipher.equals(m_cipher)) {
            return;
        }
        synchronized (m_workflowMutex) {
            setDirtyAll(); // first set dirty to decrypt with old cipher
            m_cipher = cipher;
            String msg = " cipher on meta node \"" + getNameWithID() + "\"";
            if (m_cipher.isNullCipher()) {
                LOGGER.debug("Unsetting " + msg);
            } else {
                LOGGER.debug("Setting " + msg);
            }
        }
        notifyNodePropertyChangedListener(NodeProperty.LockStatus);
        notifyWorkflowListeners(new WorkflowEvent(
                WorkflowEvent.Type.WORKFLOW_DIRTY, getID(), null, null));
    }

    /** {@inheritDoc}
     * @noreference This method is not intended to be referenced by clients.
     * @since 2.10 */
    @Override
    public WorkflowCipher getWorkflowCipher() {
        return m_cipher;
    }

    /** @return see {@link WorkflowCipher#isUnlocked()}. */
    @SuppressWarnings("javadoc")
    public boolean isUnlocked() {
        return m_cipher.isUnlocked();
    }

    /** @return see {@link WorkflowCipher#getPasswordHint()}. */
    @SuppressWarnings("javadoc")
    public String getPasswordHint() {
        return m_cipher.getPasswordHint();
    }

    /** @param prompt The prompt
     * @return see {@link WorkflowCipher#unlock(WorkflowCipherPrompt)}. */
    @SuppressWarnings("javadoc")
    public boolean unlock(final WorkflowCipherPrompt prompt) {
        boolean isNowUnlocked;
        boolean wasUnlocked;
        synchronized (m_workflowMutex) {
            wasUnlocked = isUnlocked();
            isNowUnlocked = m_cipher.unlock(prompt);
        }
        if (wasUnlocked != isNowUnlocked) {
            notifyNodePropertyChangedListener(NodeProperty.LockStatus);
        }
        return isNowUnlocked;
    }

    /** @return see {@link WorkflowCipher#isEncrypted()}. */
    @SuppressWarnings("javadoc")
    public boolean isEncrypted() {
        return this != ROOT && m_cipher.isEncrypted();
    }

    /** {@inheritDoc} */
    @Override
    public OutputStream cipherOutput(final OutputStream out) throws IOException {
        return m_cipher.cipherOutput(this, out);
    }

    /** {@inheritDoc} */
    @Override
    public String getCipherFileName(final String fileName) {
        return WorkflowCipher.getCipherFileName(this, fileName);
    }

    ///////////////////////////////////
    // Listener for Workflow Events
    ///////////////////////////////////

    /**
     * Add listener to list.
     *
     * @param listener new listener
     */
    public void addListener(final WorkflowListener listener) {
        if (!m_wfmListeners.contains(listener)) {
            m_wfmListeners.add(listener);
        }
    }

    /**
     * Remove listener.
     * @param listener listener to be removed
     */
    public void removeListener(final WorkflowListener listener) {
        m_wfmListeners.remove(listener);
    }

    /**
     * Fire event to all listeners.
     * @param evt event
     */
    private final void notifyWorkflowListeners(final WorkflowEvent evt) {
        if (m_wfmListeners.isEmpty()) {
            return;
        }
        // the iterator is based on the current(!) set of listeners
        // (problem was: during load the addNodeContainer method fired an event
        // by using this method - the event got delivered at a point where
        // the workflow editor was registered and marked the flow as being dirty
        // although it was freshly loaded)
        final Iterator<WorkflowListener> it = m_wfmListeners.iterator();
        WORKFLOW_NOTIFIER.execute(new Runnable() {
            /** {@inheritDoc} */
            @Override
            public void run() {
                while (it.hasNext()) {
                    it.next().workflowChanged(evt);
                }
            }
        });
    }

    // bug fix 1810, nofify children about possible job manager changes
    /** {@inheritDoc} */
    @Override
    protected void notifyNodePropertyChangedListener(
            final NodeProperty property) {
        super.notifyNodePropertyChangedListener(property);
        switch (property) {
        case JobManager:
            // TODO protect for intermediate changes to the node list
            for (NodeContainer nc : getNodeContainers()) {
                nc.notifyNodePropertyChangedListener(property);
            }
            break;
        case TemplateConnection:
            for (NodeContainer nc : getNodeContainers()) {
                // only sub-workflows care as they possibly switch from
                // write-protected to editable
                if (nc instanceof WorkflowManager) {
                    nc.notifyNodePropertyChangedListener(property);
                }
            }
            break;
        default:
            // ignore children notification
        }
    }

    //////////////////////////////////////
    // copy & paste
    //////////////////////////////////////

    /** {@inheritDoc} */
    @Override
    protected CopyWorkflowPersistor getCopyPersistor(
            final HashMap<Integer, ContainerTable> tableRep,
            final FileStoreHandlerRepository fileStoreHandlerRepository,
            final boolean preserveDeletableFlags,
            final boolean isUndoableDeleteCommand) {
        return new CopyWorkflowPersistor(this, tableRep,
                fileStoreHandlerRepository,
                preserveDeletableFlags, isUndoableDeleteCommand);
    }

    /** Copies the nodes with the given ids from the argument workflow manager
     * into this wfm instance. All nodes wil be reset (and configured id
     * possible). Connections among the nodes are kept.
     * @param sourceManager The wfm to copy from
     * @param content The content to copy (must exist in sourceManager)
     * @return Inserted NodeIDs and annotations.
     */
    public WorkflowCopyContent copyFromAndPasteHere(
            final WorkflowManager sourceManager,
            final WorkflowCopyContent content) {
        WorkflowPersistor copyPersistor = sourceManager.copy(content);
        return paste(copyPersistor);
    }

    /** Copy the given content.
     * @param content The content to copy (must exist).
     * @return A workflow persistor hosting the node templates, ready to be
     * used in the {@link #paste(WorkflowPersistor)} method.
     */
    public WorkflowPersistor copy(final WorkflowCopyContent content) {
        return copy(false, content);
    }

    /** Copy the nodes with the given ids.
     * @param isUndoableDeleteCommand True if the returned persistor is used
     * in the delete command (which supports undo). This has two effects:
     * <ol>
     *   <li>It keeps the locations of the node's directories (e.g.
     *   &lt;workflow>/File Reader (#xy)/). This is true if the copy serves
     *   as backup of an undoable delete command (undoable = undo enabled).
     *   If it is undone, the directories must not be cleared before the
     *   next save (in order to keep the drop folder)
     *   </li>
     *   <li>The returned persistor will insert a reference to the contained
     *   workflow annotations instead of copying them (enables undo on previous
     *   move or edit commands.
     *   </li>
     * </ol>
     * @param content The content to copy (must exist).
     * @return A workflow persistor hosting the node templates, ready to be
     * used in the {@link #paste(WorkflowPersistor)} method.
     */
    public WorkflowPersistor copy(final boolean isUndoableDeleteCommand,
            final WorkflowCopyContent content) {
        NodeID[] nodeIDs = content.getNodeIDs();
        HashSet<NodeID> idsHashed = new HashSet<NodeID>(Arrays.asList(nodeIDs));
        if (idsHashed.size() != nodeIDs.length) {
            throw new IllegalArgumentException(
                    "argument list contains duplicates");
        }
        Map<Integer, NodeContainerPersistor> loaderMap =
            new LinkedHashMap<Integer, NodeContainerPersistor>();
        Set<ConnectionContainerTemplate> connTemplates =
            new HashSet<ConnectionContainerTemplate>();
        Set<ConnectionContainerTemplate> additionalConnTemplates =
            new HashSet<ConnectionContainerTemplate>();
        boolean isIncludeInOut = content.isIncludeInOutConnections();
        synchronized (m_workflowMutex) {
            for (int i = 0; i < nodeIDs.length; i++) {
                // throws exception if not present in workflow
                NodeContainer cont = getNodeContainer(nodeIDs[i]);
                final NodeContainerPersistor copyPersistor = cont.getCopyPersistor(
                    m_globalTableRepository, m_fileStoreHandlerRepository, false, isUndoableDeleteCommand);
                NodeContainerMetaPersistor copyMetaPersistor = copyPersistor.getMetaPersistor();
                NodeUIInformation overwrittenUIInfo = content.getOverwrittenUIInfo(nodeIDs[i]);
                Integer suggestedNodeIDSuffix = content.getSuggestedNodIDSuffix(nodeIDs[i]);
                if (overwrittenUIInfo != null) {
                    copyMetaPersistor.setUIInfo(overwrittenUIInfo);
                }
                int nodeIDSuffix;
                if (suggestedNodeIDSuffix != null) {
                    nodeIDSuffix = suggestedNodeIDSuffix.intValue();
                    copyMetaPersistor.setNodeIDSuffix(nodeIDSuffix);
                } else {
                    nodeIDSuffix = cont.getID().getIndex();
                }
                loaderMap.put(nodeIDSuffix, copyPersistor);
                for (ConnectionContainer out
                        : m_workflow.getConnectionsBySource(nodeIDs[i])) {
                    if (idsHashed.contains(out.getDest())) {
                        ConnectionContainerTemplate t =
                            new ConnectionContainerTemplate(out, false);
                        connTemplates.add(t);
                    } else if (isIncludeInOut) {
                        ConnectionContainerTemplate t =
                            new ConnectionContainerTemplate(out, false);
                        additionalConnTemplates.add(t);
                    }
                }
                if (isIncludeInOut) {
                    for (ConnectionContainer in
                            : m_workflow.getConnectionsByDest(nodeIDs[i])) {
                        ConnectionContainerTemplate t =
                            new ConnectionContainerTemplate(in, false);
                        additionalConnTemplates.add(t);
                    }
                }
            }
            return new PasteWorkflowContentPersistor(loaderMap, connTemplates,
                    additionalConnTemplates, content.getAnnotations(),
                    isUndoableDeleteCommand);
        }
    }

    /** Pastes the contents of the argument persistor into this wfm.
     * @param persistor The persistor created with
     * {@link #copy(WorkflowCopyContent)} method.
     * @return The new node ids of the inserted nodes and the annotations in a
     *         dedicated object.
     */
    public WorkflowCopyContent paste(final WorkflowPersistor persistor) {
        synchronized (m_workflowMutex) {
            try {
                return loadContent(persistor,
                        new HashMap<Integer, BufferedDataTable>(),
                        new FlowObjectStack(getID()), new ExecutionMonitor(),
                        new LoadResult("Paste into Workflow"), false);
            } catch (CanceledExecutionException e) {
                throw new IllegalStateException("Cancelation although no access"
                        + " on execution monitor");
            }
        }
    }

    ///////////////////////////////
    ///////// LOAD & SAVE /////////
    ///////////////////////////////

    /** Get working folder associated with this WFM. May be null if
     * not saved yet.
     * @return working directory.
     */
    public ReferencedFile getWorkingDir() {
        return getNodeContainerDirectory();
    }

    /** @return the authorInformation or null if not saved yet.
     * @noreference This method is not intended to be referenced by clients.
     * @since 2.8
     */
    public AuthorInformation getAuthorInformation() {
        return m_authorInformation;
    }

    /** Workflow version, indicates the "oldest"
      * version that is compatible to the current workflow format. */
    static final String CFG_VERSION = "version";
    /** Version of KNIME that has written the workflow. */
    static final String CFG_CREATED_BY = "created_by";
    /** Timestamp when the workflow was written. */
    static final String CFG_WRITTEN_ON = "written_on";

    /**
     * @param directory The directory to load from
     * @param exec The execution monitor
     * @param loadHelper The load helper
     * @return Result of the load operation
     * @throws IOException If an IO error occurs
     * @throws InvalidSettingsException If invalid settings were found
     * @throws CanceledExecutionException If execution was canceled
     * @throws UnsupportedWorkflowVersionException If the workflow has an unsupported version
     * @throws LockFailedException If locking failed
     */
    public static WorkflowLoadResult loadProject(final File directory,
            final ExecutionMonitor exec, final WorkflowLoadHelper loadHelper)
            throws IOException, InvalidSettingsException,
            CanceledExecutionException, UnsupportedWorkflowVersionException,
            LockFailedException {
        WorkflowLoadResult result = ROOT.load(directory, exec, loadHelper, false);
        return result;
    }

    /**
     * Creates a flow private sub dir in the temp folder. Sets it in the context. FileUtil#createTempDir picks it up
     * from there. If the temp file location in the context is already set, this method does nothing.
     * @param context to set the new temp dir location in
     * @throws IllegalStateException if temp folder can't be created.
     */
    private void createAndSetWorkflowTempDirectory(final WorkflowContext context) {
        if (context.getTempLocation() != null) {
            return;
        }
        File rootDir = new File(KNIMEConstants.getKNIMETempDir());
        File tempDir;
        try {
            tempDir = FileUtil.createTempDir("knime_" + FileUtil.getValidFileName(getName(), 15), rootDir);
        } catch (IOException e) {
            throw new IllegalStateException("Can't create temp folder in " + rootDir.getAbsolutePath(), e);
        }
        context.setTempLocation(tempDir);
        // if we created the temp dir we must clean it up when disposing of the workflow
        m_tmpDir = tempDir;
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowExecutionResult createExecutionResult(
            final ExecutionMonitor exec) throws CanceledExecutionException {
        synchronized (m_nodeMutex) {
            WorkflowExecutionResult result =
                new WorkflowExecutionResult(getID());
            super.saveExecutionResult(result);
            Set<NodeID> bfsSortedSet = m_workflow.createBreadthFirstSortedList(
                    m_workflow.getNodeIDs(), true).keySet();
            boolean success = false;
            for (NodeID id : bfsSortedSet) {
                NodeContainer nc = getNodeContainer(id);
                exec.setMessage(nc.getNameWithID());
                ExecutionMonitor subExec = exec.createSubProgress(
                        1.0 / bfsSortedSet.size());
                NodeContainerExecutionResult subResult =
                    getNodeContainer(id).createExecutionResult(subExec);
                if (subResult.isSuccess()) {
                    success = true;
                }
                result.addNodeExecutionResult(id, subResult);
            }
            // if at least one child was an success, this is also a success for
            // this node; force it -- otherwise take old success flag
            // (important for no-child workflows)
            if (success) {
                result.setSuccess(true);
            }
            return result;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void loadExecutionResult(
            final NodeContainerExecutionResult result,
            final ExecutionMonitor exec, final LoadResult loadResult) {
        if (result == null) {
            throw new IllegalArgumentException(
            "Workflow result must not be null");
        } else if (!(result instanceof WorkflowExecutionResult)) {
            throw new IllegalArgumentException("Argument must be instance "
                    + "of \"" + WorkflowExecutionResult.class.getSimpleName()
                    + "\": " + result.getClass().getSimpleName());
        }
        WorkflowExecutionResult r = (WorkflowExecutionResult)result;
        synchronized (m_workflowMutex) {
            super.loadExecutionResult(result, exec, loadResult);
            Map<NodeID, NodeContainerExecutionResult> map =
                r.getExecutionResultMap();
            final int count = map.size();
            // contains the corrected NodeID in this workflow (the node ids in
            // the execution result refer to the base id of the remote workflow)
            Map<NodeID, NodeID> transMap = new HashMap<NodeID, NodeID>();
            NodeID otherIDPrefix = r.getBaseID();
            for (NodeID otherID : map.keySet()) {
                assert otherID.hasSamePrefix(otherIDPrefix);
                transMap.put(new NodeID(getID(), otherID.getIndex()), otherID);
            }
            for (NodeID id : m_workflow.createBreadthFirstSortedList(
                    transMap.keySet(), true).keySet()) {
                NodeID otherID = transMap.get(id);
                NodeContainer nc = m_workflow.getNode(id);
                NodeContainerExecutionResult exResult = map.get(otherID);
                if (exResult == null) {
                    loadResult.addError("No execution result for node "
                            + nc.getNameWithID());
                    continue;
                }
                exec.setMessage(nc.getNameWithID());
                ExecutionMonitor subExec = exec.createSubProgress(1.0 / count);
                nc.loadExecutionResult(exResult, subExec, loadResult);
                subExec.setProgress(1.0);
            }
        }
    }

    /**Create persistor for a workflow or template.
     * @noreference Clients should only be required to load projects using
     * {@link #loadProject(File, ExecutionMonitor, WorkflowLoadHelper)}
     * @param directory The directory to load from
     * @param loadHelper The load helper
     * @return The persistor
     * @throws IOException If an IO error occured
     * @throws UnsupportedWorkflowVersionException If the workflow is of an unsupported version
     */
    public static FileWorkflowPersistor createLoadPersistor(final File directory, final WorkflowLoadHelper loadHelper)
            throws IOException, UnsupportedWorkflowVersionException {
        return (FileWorkflowPersistor)loadHelper.createLoadPersistor(directory);
    }

    /**
     * Loads the workflow contained in the directory as node into this workflow
     * instance. Loading a whole new project is usually done using
     * {@link WorkflowManager#loadProject(File, ExecutionMonitor, WorkflowLoadHelper)}
     * .
     *
     * @param directory to load from
     * @param exec For progress/cancellation (currently not supported)
     * @param loadHelper callback to load credentials and such (if available)
     *            during load of the underlying <code>SingleNodeContainer</code>
     *            (may be null).
     * @param keepNodeMessages Whether to keep the messages that are associated
     *            with the nodes in the loaded workflow (mostly false but true
     *            when remotely computed results are loaded).
     * @return A workflow load result, which also contains the loaded workflow.
     * @throws IOException If errors reading the "important" files fails due to
     *             I/O problems (file not present, e.g.)
     * @throws InvalidSettingsException If parsing the "important" files fails.
     * @throws CanceledExecutionException If canceled.
     * @throws UnsupportedWorkflowVersionException If the version of the
     *             workflow is unknown (future version)
     * @throws LockFailedException if the flow can't be locked for opening
     */
    public WorkflowLoadResult load(final File directory, final ExecutionMonitor exec,
        final WorkflowLoadHelper loadHelper, final boolean keepNodeMessages) throws IOException,
        InvalidSettingsException, CanceledExecutionException, UnsupportedWorkflowVersionException, LockFailedException {
        ReferencedFile rootFile = new ReferencedFile(directory);
        boolean isTemplate = loadHelper.isTemplateFlow();
        if (!isTemplate) {
            // don't lock read-only templates (as we don't have r/o locks yet)
            if (!rootFile.fileLockRootForVM()) {
                StringBuilder error = new StringBuilder();
                error.append("Unable to lock workflow from \"");
                error.append(rootFile).append("\". ");
                if (rootFile.getFile().exists()) {
                    error.append("It is in use by another user/instance.");
                } else {
                    error.append("Location does not exist.");
                }
                throw new LockFailedException(error.toString());
            }
        }
        try {
            FileWorkflowPersistor persistor = createLoadPersistor(directory, loadHelper);
            return load(persistor, exec, keepNodeMessages);
        } finally {
            if (!isTemplate) {
                rootFile.fileUnlockRootForVM();
            }
        }
    }

    /**
     * Loads the content of the argument persistor into this node.
     *
     * @param persistor The persistor containing the node(s) to be loaded as children to this node.
     * @param exec For progress/cancellation (currently not supported)
     * @param keepNodeMessages Whether to keep the messages that are associated with the nodes in the loaded workflow
     *            (mostly false but true when remotely computed results are loaded).
     * @return A workflow load result, which also contains the loaded node(s).
     * @throws IOException If errors reading the "important" files fails due to I/O problems (file not present, e.g.)
     * @throws InvalidSettingsException If parsing the "important" files fails.
     * @throws CanceledExecutionException If canceled.
     * @throws UnsupportedWorkflowVersionException If the version of the workflow is unknown (future version)
     */
    public WorkflowLoadResult load(final FileWorkflowPersistor persistor, final ExecutionMonitor exec,
        final boolean keepNodeMessages) throws IOException, InvalidSettingsException, CanceledExecutionException,
        UnsupportedWorkflowVersionException {
        final ReferencedFile refDirectory = persistor.getMetaPersistor().getNodeContainerDirectory();
        final File directory = refDirectory.getFile();
        final WorkflowLoadResult result = new WorkflowLoadResult(directory.getName());
        load(persistor, result, exec, keepNodeMessages);
        final WorkflowManager manager = result.getWorkflowManager();
        if (!directory.canWrite()) {
            result.addWarning("Workflow directory \"" + directory.getName()
                + "\" is read-only; saving a modified workflow " + "will not be possible");
            manager.m_isWorkflowDirectoryReadonly = true;
        }
        boolean fixDataLoadProblems = false;
        // if all errors during the load process are related to data loading
        // it might be that the flow is ex/imported without data;
        // check for it and silently overwrite the workflow
        switch (result.getType()) {
            case DataLoadError:
                if (!persistor.mustWarnOnDataLoadError() && !manager.m_isWorkflowDirectoryReadonly) {
                    LOGGER.debug("Workflow was apparently ex/imported without "
                            + "data, silently fixing states and writing changes");
                    try {
                        manager.save(directory, new ExecutionMonitor(), true);
                        fixDataLoadProblems = true;
                    } catch (Throwable t) {
                        LOGGER.warn("Failed in an attempt to write workflow to file (workflow was ex/imported "
                                + "without data; could not write the \"corrected\" flow.)", t);
                    }
                }
                break;
            default:
                // errors are handled elsewhere
        }
        StringBuilder message = new StringBuilder("Loaded workflow from \"");
        message.append(directory.getAbsolutePath()).append("\" ");
        switch (result.getType()) {
            case Ok:
                message.append(" with no errors");
                break;
            case Warning:
                message.append(" with warnings");
                break;
            case DataLoadError:
                message.append(" with errors during data load. ");
                if (fixDataLoadProblems) {
                    message.append("Problems were fixed and (silently) saved.");
                } else {
                    message.append("Problems were fixed but not saved!");
                }
                break;
            case Error:
                message.append(" with errors");
                break;
            default:
                message.append("with ").append(result.getType());
        }
        LOGGER.debug(message.toString());        return result;
    }

    /** Implementation of {@link #load(FileWorkflowPersistor, ExecutionMonitor, boolean)}.
     * @noreference This method is not intended to be referenced by clients. */
    public void load(final TemplateNodeContainerPersistor persistor,
        final MetaNodeLinkUpdateResult result, final ExecutionMonitor exec,
        final boolean keepNodeMessages) throws IOException, InvalidSettingsException, CanceledExecutionException,
        UnsupportedWorkflowVersionException {
        final ReferencedFile refDirectory = persistor.getMetaPersistor().getNodeContainerDirectory();
        exec.setMessage("Loading workflow structure from \"" + refDirectory + "\"");
        exec.checkCanceled();
        LoadVersion version = persistor.getLoadVersion();
        LOGGER.debug("Loading workflow from \"" + refDirectory + "\" (version \"" + version + "\" with loader class \""
            + persistor.getClass().getSimpleName() + "\")");
        // data files are loaded using a repository of reference tables;
        Map<Integer, BufferedDataTable> tblRep = new HashMap<Integer, BufferedDataTable>();
        persistor.preLoadNodeContainer(null, null, result);
        NodeContainerTemplate loadedInstance = null;
        boolean isIsolatedProject = persistor.isProject();
        InsertWorkflowPersistor insertPersistor = new InsertWorkflowPersistor(persistor);
        Object mutex = isIsolatedProject ? new Object() : m_workflowMutex;
        synchronized (mutex) {
            m_loadVersion = persistor.getLoadVersion();
            NodeID[] newIDs = loadContent(insertPersistor, tblRep, null, exec, result, keepNodeMessages).getNodeIDs();
            if (newIDs.length != 1) {
                throw new InvalidSettingsException("Loading workflow failed, "
                    + "couldn't identify child sub flow (typically " + "a project)");
            }
            loadedInstance = (NodeContainerTemplate)getNodeContainer(newIDs[0]);
        }
        exec.setProgress(1.0);
        result.setLoadedInstance(loadedInstance);
        result.setGUIMustReportDataLoadErrors(persistor.mustWarnOnDataLoadError());
    }

    /** {@inheritDoc} */
    @Override
    WorkflowCopyContent loadContent(final NodeContainerPersistor nodePersistor,
            final Map<Integer, BufferedDataTable> tblRep,
            final FlowObjectStack ignoredStack, final ExecutionMonitor exec,
            final LoadResult loadResult, final boolean preserveNodeMessage)
        throws CanceledExecutionException {
        exec.checkCanceled();
        if (!(nodePersistor instanceof WorkflowPersistor)) {
            throw new IllegalStateException("Expected " + WorkflowPersistor.class.getSimpleName()
                + " persistor object, got " + nodePersistor.getClass().getSimpleName());
        }
        WorkflowPersistor persistor = (WorkflowPersistor)nodePersistor;
        assert this != ROOT || persistor.getConnectionSet().isEmpty()
        : "ROOT workflow has no connections: " + persistor.getConnectionSet();
        LinkedHashMap<NodeID, NodeContainerPersistor> persistorMap =
            new LinkedHashMap<NodeID, NodeContainerPersistor>();
        Map<Integer, ? extends NodeContainerPersistor> nodeLoaderMap = persistor.getNodeLoaderMap();
        exec.setMessage("annotations");
        List<WorkflowAnnotation> annos = persistor.getWorkflowAnnotations();
        for (WorkflowAnnotation w : annos) {
            addWorkflowAnnotationInternal(w);
        }
        exec.setMessage("node & connection information");
        Map<Integer, NodeID> translationMap = loadNodesAndConnections(
                nodeLoaderMap, persistor.getConnectionSet(), loadResult);
        for (Map.Entry<Integer, NodeID> e : translationMap.entrySet()) {
            NodeID id = e.getValue();
            NodeContainerPersistor p = nodeLoaderMap.get(e.getKey());
            assert p != null : "Deficient translation map";
            persistorMap.put(id, p);
        }
        persistor.postLoad(this, loadResult);
        try {
            postLoad(persistorMap, tblRep, persistor.mustWarnOnDataLoadError(),
                    exec, loadResult, preserveNodeMessage);
        } catch (CanceledExecutionException cee) {
            for (NodeID insertedNodeID : translationMap.values()) {
                removeNode(insertedNodeID);
            }
            throw cee;
        }
        NodeSettingsRO wizardState = persistor.getWizardExecutionControllerState();
        if (wizardState != null) {
            try {
                m_wizardExecutionController = new WizardExecutionController(this, wizardState);
            } catch (InvalidSettingsException e1) {
                String msg = "Failed to restore wizard controller from file: " + e1.getMessage();
                LOGGER.debug(msg, e1);
                loadResult.addError(msg);
            }
        }
        // set dirty if this wm should be reset (for instance when the state
        // of the workflow can't be properly read from the workflow.knime)
        if (persistor.needsResetAfterLoad() || persistor.isDirtyAfterLoad()) {
            setDirty();
        }
        ReferencedFile ncDirectory = getNodeContainerDirectory();
        if (ncDirectory != null) {
            ncDirectory.getDeletedNodesFileLocations().addAll(persistor.getObsoleteNodeDirectories());
        }
        ReferencedFile autoSaveDirectory = getAutoSaveDirectory();
        if (autoSaveDirectory != null) {
            autoSaveDirectory.getDeletedNodesFileLocations().addAll(persistor.getObsoleteNodeDirectories());
        }
        Collection<NodeID> resultColl = persistorMap.keySet();
        NodeID[] newIDs = resultColl.toArray(new NodeID[resultColl.size()]);
        WorkflowAnnotation[] newAnnotations = annos.toArray(new WorkflowAnnotation[annos.size()]);
        addConnectionsFromTemplates(persistor.getAdditionalConnectionSet(), loadResult, translationMap, false);
        WorkflowCopyContent result = new WorkflowCopyContent();
        result.setAnnotation(newAnnotations);
        result.setNodeIDs(newIDs);
        return result;
    }

    private void postLoad(final Map<NodeID, NodeContainerPersistor> persistorMap,
                          final Map<Integer, BufferedDataTable> tblRep, final boolean mustWarnOnDataLoadError,
                          final ExecutionMonitor exec, final LoadResult loadResult, final boolean keepNodeMessage)
            throws CanceledExecutionException {
        // linked set because we need reverse order later on
        Collection<NodeID> failedNodes = new LinkedHashSet<NodeID>();
        boolean isStateChangePredictable = false;
        final Set<NodeID> nodeIDsInPersistorSet = persistorMap.keySet();
        // had NPE below - adding this line to get better debug information
        CheckUtils.checkArgumentNotNull(nodeIDsInPersistorSet,
            "NodeID list from persistor must not be null for workflow %s", getNameWithID());
        for (NodeID bfsID : m_workflow.createBreadthFirstSortedList(nodeIDsInPersistorSet, true).keySet()) {
            NodeContainer cont = getNodeContainer(bfsID);
            // initialize node container with CredentialsStore
            if (cont instanceof SingleNodeContainer) {
                SingleNodeContainer snc = (SingleNodeContainer)cont;
                snc.setCredentialsStore(m_credentialsStore);
            }
            LoadResult subResult = new LoadResult(cont.getNameWithID());
            boolean isFullyConnected = isFullyConnected(bfsID);
            boolean needsReset;
            switch (cont.getInternalState()) {
                case IDLE:
                case UNCONFIGURED_MARKEDFOREXEC:
                    needsReset = false;
                    break;
                default:
                    // we reset everything which is not fully connected
                    needsReset = !isFullyConnected;
                    break;
            }
            NodeOutPort[] predPorts = assemblePredecessorOutPorts(bfsID);
            final int predCount = predPorts.length;
            PortObject[] portObjects = new PortObject[predCount];
            boolean inPortsContainNull = false;
            FlowObjectStack[] predStacks = new FlowObjectStack[predCount];
            for (int i = 0; i < predCount; i++) {
                NodeOutPort p = predPorts[i];
                if (cont instanceof SingleNodeContainer && p != null) {
                    SingleNodeContainer snc = (SingleNodeContainer)cont;
                    snc.setInHiLiteHandler(i, p.getHiLiteHandler());
                }
                if (p != null) {
                    predStacks[i] = p.getFlowObjectStack();
                    portObjects[i] = p.getPortObject();
                    inPortsContainNull &= portObjects[i] == null;
                }
            }
            FlowObjectStack inStack;
            try {
                if (isSourceNode(bfsID)) {
                    predStacks = new FlowObjectStack[]{getWorkflowVariableStack()};
                }
                inStack = new FlowObjectStack(cont.getID(), predStacks);
            } catch (IllegalFlowObjectStackException ex) {
                subResult.addError("Errors creating flow object stack for " + "node \"" + cont.getNameWithID()
                        + "\", (resetting " + "flow variables): " + ex.getMessage());
                needsReset = true;
                inStack = new FlowObjectStack(cont.getID());
            }
            NodeContainerPersistor persistor = persistorMap.get(bfsID);
            InternalNodeContainerState loadState = persistor.getMetaPersistor().getState();
            exec.setMessage(cont.getNameWithID());
            exec.checkCanceled();
            // two steps below: loadNodeContainer and loadContent
            ExecutionMonitor sub1 = exec.createSubProgress(1.0 / (2 * m_workflow.getNrNodes()));
            ExecutionMonitor sub2 = exec.createSubProgress(1.0 / (2 * m_workflow.getNrNodes()));
            NodeContext.pushContext(cont);
            try {
                persistor.loadNodeContainer(tblRep, sub1, subResult);
            } catch (CanceledExecutionException e) {
                throw e;
            } catch (Exception e) {
                if (!(e instanceof InvalidSettingsException) && !(e instanceof IOException)) {
                    LOGGER.error("Caught unexpected \"" + e.getClass().getSimpleName() + "\" during node loading", e);
                }
                subResult.addError("Errors loading, skipping it: " + e.getMessage());
                needsReset = true;
            } finally {
                NodeContext.removeLastContext();
            }
            sub1.setProgress(1.0);
            // if cont == isolated meta nodes, then we need to block that meta
            // node as well (that is being asserted in methods which get called
            // indirectly)
            Object mutex = cont instanceof WorkflowManager ? ((WorkflowManager)cont).m_workflowMutex : m_workflowMutex;
            synchronized (mutex) {
                cont.loadContent(persistor, tblRep, inStack, sub2, subResult, keepNodeMessage);
            }
            sub2.setProgress(1.0);
            if (persistor.isDirtyAfterLoad()) {
                cont.setDirty();
            }
            boolean hasPredecessorFailed = false;
            for (ConnectionContainer cc : m_workflow.getConnectionsByDest(bfsID)) {
                NodeID s = cc.getSource();
                if (s.equals(getID())) {
                    continue; // don't consider WFM_IN connections
                }
                if (failedNodes.contains(s)) {
                    hasPredecessorFailed = true;
                }
            }
            needsReset |= persistor.needsResetAfterLoad();
            needsReset |= hasPredecessorFailed;
            boolean isExecuted = cont.getInternalState().equals(InternalNodeContainerState.EXECUTED);
            boolean remoteExec = persistor.getMetaPersistor().getExecutionJobSettings() != null;

            // if node is executed and some input data is missing we need
            // to reset that node as there is obviously a conflict (e.g.
            // predecessors has been loaded as IDLE
            if (!needsReset && isExecuted && inPortsContainNull) {
                needsReset = true;
                subResult.addError("Predecessor ports have no data", true);
            }
            if (needsReset && cont instanceof NativeNodeContainer && cont.isResetable()) {
                // we don't care for successors because they are not loaded yet
                invokeResetOnSingleNodeContainer((SingleNodeContainer)cont);
                isExecuted = false;
            }
            if (needsReset) {
                failedNodes.add(bfsID);
            }
            if (!isExecuted && cont instanceof SingleNodeContainer) {
                configureSingleNodeContainer((SingleNodeContainer)cont, keepNodeMessage);
            }
            if (persistor.mustComplainIfStateDoesNotMatch() && !cont.getInternalState().equals(loadState)
                    && !hasPredecessorFailed) {
                isStateChangePredictable = true;
                String warning = "State has changed from " + loadState + " to " + cont.getInternalState();
                switch (subResult.getType()) {
                    case DataLoadError:
                        // data load errors cause state changes
                        subResult.addError(warning, true);
                        break;
                    default:
                        subResult.addWarning(warning);
                }
                cont.setDirty();
            }
            // saved in executing state (e.g. grid job), request to reconnect
            if (remoteExec) {
                if (needsReset) {
                    subResult.addError("Can't continue execution " + "due to load errors");
                }
                if (inPortsContainNull) {
                    subResult.addError("Can't continue execution; no data in inport");
                }
                if (!cont.getInternalState().equals(InternalNodeContainerState.EXECUTINGREMOTELY)) {
                    subResult.addError("Can't continue execution; node is not " + "configured but "
                            + cont.getInternalState());
                }
                try {
                    if (!continueExecutionOnLoad(cont, persistor)) {
                        cont.cancelExecution();
                        cont.setDirty();
                        subResult.addError("Can't continue execution; unknown reason");
                    }
                } catch (Exception exc) {
                    StringBuilder error = new StringBuilder("Can't continue execution");
                    if (exc instanceof NodeExecutionJobReconnectException || exc instanceof InvalidSettingsException) {
                        error.append(": ").append(exc.getMessage());
                    } else {
                        error.append(" due to ");
                        error.append(exc.getClass().getSimpleName());
                        error.append(": ").append(exc.getMessage());
                    }
                    LOGGER.error(error, exc);
                    cont.cancelExecution();
                    cont.setDirty();
                    subResult.addError(error.toString());
                }
            }
            loadResult.addChildError(subResult);
            // set warning message on node if we have loading errors
            // do this only if these are critical errors or data-load errors,
            // which must be reported.
            switch (subResult.getType()) {
                case Ok:
                case Warning:
                    break;
                case DataLoadError:
                    if (!mustWarnOnDataLoadError) {
                        break;
                    }
                default:
                    NodeMessage oldMessage = cont.getNodeMessage();
                    StringBuilder messageBuilder = new StringBuilder(oldMessage.getMessage());
                    if (messageBuilder.length() != 0) {
                        messageBuilder.append("\n");
                    }
                    NodeMessage.Type type;
                    switch (oldMessage.getMessageType()) {
                        case RESET:
                        case WARNING:
                            type = NodeMessage.Type.WARNING;
                            break;
                        default:
                            type = NodeMessage.Type.ERROR;
                    }
                    messageBuilder.append(subResult.getFilteredError("", LoadResultEntryType.Warning));
                    cont.setNodeMessage(new NodeMessage(type, messageBuilder.toString()));
            }
        }
        if (!sweep(nodeIDsInPersistorSet, false) && !isStateChangePredictable) {
            loadResult.addWarning("Some node states were invalid");
        }
    }

    private Map<Integer, NodeID> loadNodesAndConnections(
            final Map<Integer, ? extends NodeContainerPersistor> loaderMap,
            final Set<ConnectionContainerTemplate> connections,
            final LoadResult loadResult) {
        // id suffix are made unique by using the entries in this map
        @SuppressWarnings("serial")
        Map<Integer, NodeID> translationMap =
            new LinkedHashMap<Integer, NodeID>() {
            /** {@inheritDoc} */
            @Override
            public NodeID get(final Object key) {
                NodeID result = super.get(key);
                if (result == null) {
                    result = new NodeID(getID(), (Integer)key);
                }
                return result;
            }
        };

        List<ReferencedFile> deletedFilesInNCDir = getNodeContainerDirectory() == null
                ? Collections.<ReferencedFile>emptyList() : getNodeContainerDirectory().getDeletedNodesFileLocations();
        List<ReferencedFile> deletedFilesInAutoSaveDir = getAutoSaveDirectory() == null
                ? Collections.<ReferencedFile>emptyList() : getAutoSaveDirectory().getDeletedNodesFileLocations();

        for (Map.Entry<Integer, ? extends NodeContainerPersistor> nodeEntry : loaderMap.entrySet()) {
            int suffix = nodeEntry.getKey();
            NodeID subId = new NodeID(getID(), suffix);

            // the mutex may be already held here. It is not held if we load
            // a completely new project (for performance reasons when loading
            // 100+ workflows simultaneously in a cluster environment)
            synchronized (m_workflowMutex) {
                if (m_workflow.containsNodeKey(subId)) {
                    subId = m_workflow.createUniqueID();
                }
                NodeContainerPersistor pers = nodeEntry.getValue();
                translationMap.put(suffix, subId);
                NodeContainer container = pers.getNodeContainer(this, subId);
                NodeContainerMetaPersistor metaPersistor = pers.getMetaPersistor();
                ReferencedFile ncRefDir = metaPersistor.getNodeContainerDirectory();
                if (ncRefDir != null) {
                    // the nc dir is in the deleted locations list if the node was deleted and is now restored (undo)
                    deletedFilesInNCDir.remove(ncRefDir);
                    deletedFilesInAutoSaveDir.remove(ncRefDir);
                }
                addNodeContainer(container, false);
                if (pers.isDirtyAfterLoad()) {
                    container.setDirty();
                }
            }
        }

        addConnectionsFromTemplates(
                connections, loadResult, translationMap, true);
        return translationMap;
    }

    /**
     * @param connections
     * @param loadResult
     * @param translationMap
     */
    private void addConnectionsFromTemplates(final Set<ConnectionContainerTemplate> connections,
        final LoadResult loadResult, final Map<Integer, NodeID> translationMap, final boolean currentlyLoadingFlow) {
        for (ConnectionContainerTemplate c : connections) {
            int sourceSuffix = c.getSourceSuffix();
            int destSuffix = c.getDestSuffix();
            assert sourceSuffix == -1 || sourceSuffix != destSuffix
                    : "Can't insert connection, source and destination are equal";
            ConnectionType type = ConnectionType.STD;
            NodeID source;
            NodeID dest;
            if ((sourceSuffix == -1) && (destSuffix == -1)) {
                source = getID();
                dest = getID();
                type = ConnectionType.WFMTHROUGH;
            } else if (sourceSuffix == -1) {
                source = getID();
                dest = translationMap.get(destSuffix);
                type = ConnectionType.WFMIN;
            } else if (destSuffix == -1) {
                dest = getID();
                source = translationMap.get(sourceSuffix);
                type = ConnectionType.WFMOUT;
            } else {
                dest = translationMap.get(destSuffix);
                source = translationMap.get(sourceSuffix);
            }
            if (!canAddConnection(source, c.getSourcePort(), dest, c.getDestPort(), true, currentlyLoadingFlow)) {
                String warn = "Unable to insert connection \"" + c + "\"";
                LOGGER.warn(warn);
                loadResult.addError(warn);
                continue;
            }
            ConnectionContainer cc =
                addConnection(source, c.getSourcePort(), dest, c.getDestPort(), currentlyLoadingFlow);
            cc.setUIInfo(c.getUiInfo());
            cc.setDeletable(c.isDeletable());
            assert cc.getType().equals(type);
        }
    }

    /**
     * Saves the workflow to a new location, setting the argument directory as the new NC dir. It will first copy the
     * "old" directory, point the NC dir to the new location and then do an incremental save.
     *
     * @param directory new directory, not null
     * @param exec The execution monitor
     * @throws IOException If an IO error occured
     * @throws CanceledExecutionException If the execution was canceled
     * @throws LockFailedException If locking failed
     * @since 2.9
     */
    public void saveAs(final File directory, final ExecutionMonitor exec) throws IOException,
        CanceledExecutionException, LockFailedException {
        if (this == ROOT) {
            throw new IOException("Can't save root workflow");
        }
        synchronized (m_workflowMutex) {
            ReferencedFile ncDirRef = getNodeContainerDirectory();
            if (!isProject()) {
                throw new IOException("Cannot call save-as on a non-project workflow");
            }
            directory.mkdirs();
            if (!directory.isDirectory() || !directory.canWrite()) {
                throw new IOException("Cannot write to " + directory);
            }
            boolean isNCDirNullOrRootReferenceFolder = ncDirRef == null || ncDirRef.getParent() == null;
            if (!isNCDirNullOrRootReferenceFolder) {
                throw new IOException("Referenced directory pointer is not hierarchical: " + ncDirRef);
            }
            ReferencedFile autoSaveDirRef = getAutoSaveDirectory();
            ExecutionMonitor saveExec;
            File ncDir = ncDirRef != null ? ncDirRef.getFile() : null;
            if (!ConvenienceMethods.areEqual(ncDir, directory)) { // new location
                ncDirRef.writeLock(); // cannot be null
                try {
                    ExecutionMonitor copyExec = exec.createSubProgress(0.5);
                    final String copymsg = String.format(
                        "Copying existing workflow to new location " + "(from \"%s\" to \"%s\")", ncDir, directory);
                    exec.setMessage(copymsg);
                    LOGGER.debug(copymsg);
                    copyExec.setProgress(1.0);
                    FileUtils.copyDirectory(ncDir, directory, /* all but .knimeLock */FileFilterUtils
                        .notFileFilter(FileFilterUtils.nameFileFilter(VMFileLocker.LOCK_FILE, IOCase.SENSITIVE)));
                    exec.setMessage("Incremental save");
                    ncDirRef.changeRoot(directory);
                    m_workflowContext.setCurrentLocation(directory);
                    if (autoSaveDirRef != null) {
                        File newLoc = WorkflowSaveHelper.getAutoSaveDirectory(ncDirRef);
                        final File autoSaveDir = autoSaveDirRef.getFile();
                        if (autoSaveDir.exists()) {
                            try {
                                FileUtils.moveDirectory(autoSaveDir, newLoc);
                                autoSaveDirRef.changeRoot(newLoc);
                                LOGGER.debugWithFormat("Moved auto-save directory from \"%s\" to \"%s\"",
                                    autoSaveDir.getAbsolutePath(), newLoc.getAbsolutePath());
                            } catch (IOException ioe) {
                                LOGGER.error(String.format("Couldn't move auto save directory \"%s\" to \"%s\": %s",
                                    autoSaveDir.getAbsolutePath(), newLoc.getAbsolutePath(), ioe.getMessage()), ioe);
                            }
                        } else {
                            autoSaveDirRef.changeRoot(newLoc);
                        }

                    }
                    m_isWorkflowDirectoryReadonly = false;
                } finally {
                    ncDirRef.writeUnlock();
                }
                saveExec = exec.createSubProgress(0.5);
            } else {
                saveExec = exec;
            }
            save(directory, saveExec, true);
        }
    }

    /**
     * @param directory The directory to save in
     * @param exec The execution monitor
     * @param isSaveData ...
     * @throws IOException If an IO error occured
     * @throws CanceledExecutionException If the execution was canceled
     * @throws LockFailedException If locking failed
     */
    public void save(final File directory, final ExecutionMonitor exec, final boolean isSaveData) throws IOException,
        CanceledExecutionException, LockFailedException {
        save(directory, new WorkflowSaveHelper(isSaveData, false), exec);
    }

    /**
     * @param directory The directory to save in
     * @param exec The execution monitor
     * @param saveHelper ...
     * @throws IOException If an IO error occured
     * @throws CanceledExecutionException If the execution was canceled
     * @throws LockFailedException If locking failed
     * @since 2.10
     */
    public void save(final File directory, final WorkflowSaveHelper saveHelper,
        final ExecutionMonitor exec) throws IOException, CanceledExecutionException, LockFailedException {
        if (this == ROOT) {
            throw new IOException("Can't save root workflow");
        }
        if (m_isWorkflowDirectoryReadonly) {
            throw new IOException("Workflow is read-only, can't save");
        }
        synchronized (m_workflowMutex) {
            ReferencedFile directoryReference = new ReferencedFile(directory);
            // if it's the location associated with the workflow we will use the same instance (due to VM lock)
            if (directoryReference.equals(getNodeContainerDirectory())) {
                directoryReference = getNodeContainerDirectory();
            } else if (saveHelper.isAutoSave() && directoryReference.equals(getAutoSaveDirectory())) {
                directoryReference = getAutoSaveDirectory();
            }
            directoryReference.writeLock();
            try {
                final boolean isWorkingDirectory = directoryReference.equals(getNodeContainerDirectory());
                final LoadVersion saveVersion = FileWorkflowPersistor.VERSION_LATEST;
                if (m_loadVersion != null && !m_loadVersion.equals(saveVersion)) {
                    LOGGER.info("Workflow was created with another version of KNIME (workflow version "
                        + m_loadVersion + "), converting to current version. This may take some time.");
                    setDirtyAll();
                }
                if (isWorkingDirectory) {
                    m_loadVersion = saveVersion;
                }
                if (m_authorInformation == null) {
                    m_authorInformation = new AuthorInformation();
                } else {
                    m_authorInformation = new AuthorInformation(m_authorInformation);
                }
                directoryReference.getFile().mkdirs();
                boolean isTemplate = getTemplateInformation().getRole().equals(Role.Template);
                if (isTemplate) {
                    FileWorkflowPersistor.saveAsTemplate(this, directoryReference, exec, saveHelper);
                } else {
                    FileWorkflowPersistor.save(this, directoryReference, exec, saveHelper);
                }
            } finally {
                directoryReference.writeUnlock();
            }
        }
    }

    /**
     * Delete directories of removed nodes. This is part of the save routine to commit the changes. Called from the
     * saving persistor class. The argument list is cleared when this method returns.
     */
    static void deleteObsoleteNodeDirs(final List<ReferencedFile> deletedNodesFileLocations) {
        for (ReferencedFile deletedNodeDir : deletedNodesFileLocations) {
            File f = deletedNodeDir.getFile();
            if (f.exists()) {
                if (FileUtil.deleteRecursively(f)) {
                    LOGGER.debug("Deleted obsolete node directory \"" + f.getAbsolutePath() + "\"");
                } else {
                    LOGGER.warn("Deletion of obsolete node directory \"" + f.getAbsolutePath() + "\" failed");
                }
            }
        }
        // bug fix 1857: this list must be cleared upon save
        deletedNodesFileLocations.clear();
    }

    /** Performs sanity check on workflow. This is necessary upon load.
     * @param nodes The nodes to check
     * @param propagate Whether to also reflect state changes in our parent
     * @return Whether everything was clean before (if false is returned,
     * something was wrong).
     */
    boolean sweep(final Set<NodeID> nodes, final boolean propagate) {
        boolean wasClean = true;
        synchronized (m_workflowMutex) {
            for (NodeID id : m_workflow.createBreadthFirstSortedList(nodes, true).keySet()) {
                NodeContainer nc = getNodeContainer(id);
                if (nc instanceof WorkflowManager) {
                    WorkflowManager metaNode = (WorkflowManager)nc;
                    Set<NodeID> metaContent = metaNode.m_workflow.getNodeIDs();
                    if (!metaNode.sweep(metaContent, false)) {
                        wasClean = false;
                    }
                } else {
                    Set<InternalNodeContainerState> allowedStates =
                        new HashSet<InternalNodeContainerState>(Arrays.asList(InternalNodeContainerState.values()));
                    NodeOutPort[] predPorts = assemblePredecessorOutPorts(id);
                    for (int pi = 0; pi < predPorts.length; pi++) {
                        NodeOutPort predOutPort = predPorts[pi];
                        NodeInPort inport = nc.getInPort(pi);
                        InternalNodeContainerState predOutPortState;
                        if (predOutPort == null) { // unconnected
                            if (inport.getPortType().isOptional()) {
                                // optional inport -- imitate executed predecessor
                                predOutPortState = InternalNodeContainerState.EXECUTED;
                            } else {
                                predOutPortState = InternalNodeContainerState.IDLE;
                            }
                        } else {
                            predOutPortState = predOutPort.getNodeState();
                        }
                        switch (predOutPortState) {
                            case IDLE:
                                allowedStates.retainAll(Arrays.asList(InternalNodeContainerState.IDLE));
                                break;
                            case CONFIGURED:
                                allowedStates.retainAll(Arrays.asList(InternalNodeContainerState.CONFIGURED,
                                    InternalNodeContainerState.IDLE));
                                break;
                            case UNCONFIGURED_MARKEDFOREXEC:
                                allowedStates.retainAll(Arrays.asList(InternalNodeContainerState.IDLE,
                                    InternalNodeContainerState.UNCONFIGURED_MARKEDFOREXEC));
                                break;
                            case EXECUTED_MARKEDFOREXEC:
                            case EXECUTED_QUEUED:
                            case CONFIGURED_MARKEDFOREXEC:
                            case CONFIGURED_QUEUED:
                            case PREEXECUTE:
                            case EXECUTING:
                            case POSTEXECUTE:
                                allowedStates.retainAll(Arrays.asList(InternalNodeContainerState.IDLE,
                                    InternalNodeContainerState.UNCONFIGURED_MARKEDFOREXEC,
                                    InternalNodeContainerState.CONFIGURED,
                                    InternalNodeContainerState.CONFIGURED_MARKEDFOREXEC));
                                break;
                            case EXECUTINGREMOTELY:
                                // be more flexible than in the EXECUTING case
                                // EXECUTINGREMOTELY is used in meta nodes,
                                // which are executed elsewhere -- they set all nodes
                                // of their internal flow to EXECUTINGREMOTELY
                                allowedStates.retainAll(Arrays.asList(InternalNodeContainerState.IDLE,
                                    InternalNodeContainerState.UNCONFIGURED_MARKEDFOREXEC,
                                    InternalNodeContainerState.CONFIGURED,
                                    InternalNodeContainerState.CONFIGURED_MARKEDFOREXEC,
                                    InternalNodeContainerState.EXECUTINGREMOTELY));
                                break;
                            case EXECUTED:
                        }
                    }
                    if (!allowedStates.contains(nc.getInternalState())) {
                        wasClean = false;
                        switch (nc.getInternalState()) {
                            case EXECUTED_MARKEDFOREXEC:
                            case EXECUTED_QUEUED:
                                assert nc instanceof SingleNodeContainer;
                                ((SingleNodeContainer)nc).cancelExecution();
                            case EXECUTED:
                                resetSuccessors(nc.getID());
                                invokeResetOnNode(nc.getID());
                                break;
                            case EXECUTING:
                            case EXECUTINGREMOTELY:
                            case CONFIGURED_QUEUED:
                            case CONFIGURED_MARKEDFOREXEC:
                            case UNCONFIGURED_MARKEDFOREXEC:
                                assert nc instanceof SingleNodeContainer;
                                ((SingleNodeContainer)nc).cancelExecution();
                                break;
                            default:
                        }
                        if (!allowedStates.contains(InternalNodeContainerState.CONFIGURED)) {
                            nc.setInternalState(InternalNodeContainerState.IDLE);
                        }
                    }
                    boolean hasData = true;
                    // meta nodes don't need to provide output data and can still
                    // be executed.
                    if (nc instanceof SingleNodeContainer) {
                        for (int i = 0; i < nc.getNrOutPorts(); i++) {
                            NodeOutPort p = nc.getOutPort(i);
                            hasData &= p != null && p.getPortObject() != null && p.getPortObjectSpec() != null;
                        }
                    }
                    if (!hasData && nc.getInternalState().equals(InternalNodeContainerState.EXECUTED)) {
                        wasClean = false;
                        resetSuccessors(nc.getID());
                        invokeResetOnNode(nc.getID());
                    }
                }
            }
            checkForNodeStateChanges(propagate);
        }
        return wasClean;
    }

    /**
     * A "local wfm" is a workflow or meta node that has the default job manager. Other instances, which are executed in
     * a cluster or so, are non-local and will be more handled like a single node container on execution. The WFM
     * instance contained in a sub node is a local wfm even if the subnode has a non-default job manager set.
     * {@inheritDoc}
     */
    @Override
    protected boolean isLocalWFM() {
        return findJobManager() instanceof ThreadNodeExecutionJobManager;
    }

    /** Marks the workflow and all nodes contained as dirty in the auto-save location.
     * @noreference This method is not intended to be referenced by clients.
     * @since 2.10 */
    public void setAutoSaveDirectoryDirtyRecursivly() {
        synchronized (m_workflowMutex) {
            ReferencedFile autoSaveDirectory = getAutoSaveDirectory();
            setDirty(autoSaveDirectory);
            if (autoSaveDirectory != null) {
                for (NodeContainer nc : getNodeContainers()) {
                    nc.setDirty(nc.getAutoSaveDirectory());
                }
            }
        }
    }

    private void setDirtyAll() {
        setDirty();
        for (NodeContainer nc : m_workflow.getNodeValues()) {
            if (nc instanceof WorkflowManager) {
                ((WorkflowManager)nc).setDirtyAll();
            } else {
                nc.setDirty();
            }
        }
        for (ContainerTable t : m_globalTableRepository.values()) {
            t.ensureOpen();
        }
        for (IWriteFileStoreHandler writeFileStoreHandler : m_fileStoreHandlerRepository.getWriteFileStoreHandlers()) {
            try {
                writeFileStoreHandler.ensureOpenAfterLoad();
            } catch (IOException e) {
                LOGGER.error("Could not open file store handler " + writeFileStoreHandler, e);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setDirty() {
        boolean sendEvent = !isDirty();
        super.setDirty();
        if (sendEvent) {
            notifyWorkflowListeners(new WorkflowEvent(
                    WorkflowEvent.Type.WORKFLOW_DIRTY, getID(), null, null));
        }
    }

    //////////////////////////////////////
    // NodeContainer implementations
    // (WorkflowManager acts as meta node)
    //////////////////////////////////////

    /** {@inheritDoc} */
    @Override
    public int getNrInPorts() {
        return m_inPorts.length;
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowInPort getInPort(final int index) {
        return m_inPorts[index];
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowOutPort getOutPort(final int index) {
        return m_outPorts[index];
    }

    /** {@inheritDoc} */
    @Override
    public int getNrOutPorts() {
        return m_outPorts.length;
    }

    /** Set new name of this workflow or null to reset the name (will then
     * return the workflow directory in {@link #getName()} or null if this flow
     * has not been saved yet).
     * @param name The new name or null
     */
    public void setName(final String name) {
        if (!ConvenienceMethods.areEqual(m_name, name)) {
            m_name = name;
            setDirty();
            notifyNodePropertyChangedListener(NodeProperty.Name);
        }
    }

    /** Renames the underlying workflow directory to the new name.
     * @param newName The name of the directory.
     * @return Whether that was successful.
     * @throws IllegalStateException If the workflow has not been saved yet
     * (has no corresponding node directory).
     */
    public boolean renameWorkflowDirectory(final String newName) {
        synchronized (m_workflowMutex) {
            ReferencedFile file = getNodeContainerDirectory();
            if (file == null) {
                throw new IllegalStateException("Workflow has not been saved yet.");
            }
            return file.rename(newName);
        }
    }

    /** Get reference to credentials store used to persist name/passwords.
     * @return password store associated with this workflow/meta-node.
     */
    public CredentialsStore getCredentialsStore() {
        return m_credentialsStore;
    }

    /** Update user/password fields in the credentials store assigned to the
     * workflow and update the node configuration.
     * @param credentialsList the list of credentials to be updated. It will
     *  find matching crendentials in this workflow and update their fields.
     * @throws IllegalArgumentException If any of the credentials is unknown
     */
    public void updateCredentials(final Credentials... credentialsList) {
        synchronized (m_workflowMutex) {
            if (getCredentialsStore().update(credentialsList)) {
                configureAllNodesInWFM(false);
            }
        }
    }

    /** Get the name of the workflow. If none has been set, a name is derived
     * from the workflow directory name. If no directory has been set, a static
     * string is returned. This method never returns null.
     * {@inheritDoc} */
    @Override
    public String getName() {
        if (m_name != null) {
            return m_name;
        }
        ReferencedFile refFile = getNodeContainerDirectory();
        if (refFile != null) {
            File file = refFile.getFile();
            String dirName = file.getName();
            if (dirName != null) {
                return dirName;
            }
        }
        return "Workflow Manager";
    }

    /** @return the name set in the constructor or via {@link #setName(String)}.
     * In comparison to {@link #getName()} this method does not use the workflow
     * directory name if no other name is set.
     */
    public String getNameField() {
        return m_name;
    }

    /** {@inheritDoc} */
    @Override
    public int getNrNodeViews() {
        return 0;  // workflow managers don't have views (yet?)!
    }

    /** {@inheritDoc} */
    @Override
    public NodeView<NodeModel> getNodeView(final int i) {
        throw new IndexOutOfBoundsException("WFM don't have views.");
    }

    /** {@inheritDoc} */
    @Override
    public String getNodeViewName(final int i) {
        throw new IndexOutOfBoundsException("WFM don't have views.");
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasInteractiveView() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasInteractiveWebView() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public String getInteractiveViewName() {
        return "no view available";
    }

    /** {@inheritDoc} */
    @Override
    public <V extends AbstractNodeView<?> & InteractiveView<?, ? extends ViewContent, ? extends ViewContent>> V
        getInteractiveView() {
        return null;
    }

    /**
     * Stores the editor specific settings. Stores a reference to the object. Does not create a copy.
     * @param editorInfo the settings to store
     * @since 2.6
     */
    public void setEditorUIInformation(final EditorUIInformation editorInfo) {
        if (!ConvenienceMethods.areEqual(editorInfo, m_editorInfo)) {
            m_editorInfo = editorInfo;
            setDirty();
        }
    }

    /**
     * Returns the editor specific settings. Returns a reference to the object. Does not create a copy.
     * @return the editor settings currently stored
     * @since 2.6
     */
    public EditorUIInformation getEditorUIInformation() {
        return m_editorInfo;
    }

    /** {@inheritDoc} */
    @Override
    void loadSettings(final NodeSettingsRO settings)
        throws InvalidSettingsException {
        super.loadSettings(settings);
        NodeSettingsRO modelSettings = settings.getNodeSettings("model");
        Map<NodeID, QuickFormInputNode> nodes = findNodes(QuickFormInputNode.class, false);
        for (Entry<NodeID, QuickFormInputNode> entry : nodes.entrySet()) {
            NodeID id = entry.getKey();
            String nodeID = Integer.toString(id.getIndex());
            if (modelSettings.containsKey(nodeID)) {
                NodeSettingsRO conf = modelSettings.getNodeSettings(nodeID);
                QuickFormInputNode qfin = entry.getValue();
                NodeSettingsWO oldSettings = new NodeSettings(nodeID);
                qfin.getConfiguration().getValueConfiguration().saveValue(oldSettings);
                if (!conf.equals(oldSettings)) {
                    // FIXME: likely not here but in the WFM...
                    // not needed (actually nodes not work) because WFM itself
                    // was reset completely if any one of the settings change.
                    // SingleNodeContainer snc = (SingleNodeContainer)this.getNodeContainer(id);
                    // snc.reset();
                    @SuppressWarnings("unchecked")
                    AbstractQuickFormConfiguration<AbstractQuickFormValueInConfiguration> config =
                    (AbstractQuickFormConfiguration<AbstractQuickFormValueInConfiguration>)qfin.getConfiguration();
                    if (config != null) {
                        config.getValueConfiguration().loadValueInModel(conf);
                        saveNodeSettingsToDefault(id);
                    }
                    // see above: not needed
                    // this.configureNodeAndSuccessors(id, true);
                }
            }
        }
    }


    /** {@inheritDoc} */
    @Override
    void saveSettings(final NodeSettingsWO settings) {
        super.saveSettings(settings);

        // TODO: as we don't have a node model associated with the wfm, we must
        // do the same thing a dialog does when saving settings (it assumes
        // existance of a node).
//        Node.SettingsLoaderAndWriter l = new Node.SettingsLoaderAndWriter();
//        NodeSettings model = new NodeSettings("field_ignored");
//        NodeSettings variables;
//        l.setModelSettings(model);
//        l.setVariablesSettings(variables);
//        l.save(settings);
        NodeSettingsWO modelSettings = settings.addNodeSettings("model");
        for (Map.Entry<NodeID, QuickFormInputNode> e
                : findNodes(QuickFormInputNode.class, false).entrySet()) {
            String nodeID = Integer.toString(e.getKey().getIndex());
            NodeSettingsWO subSettings = modelSettings.addNodeSettings(nodeID);
            @SuppressWarnings("unchecked")
            AbstractQuickFormConfiguration<AbstractQuickFormValueInConfiguration> config =
                (AbstractQuickFormConfiguration<AbstractQuickFormValueInConfiguration>)e.getValue().getConfiguration();
            if (config != null) {
                config.getValueConfiguration().saveValue(subSettings);
            }
        }

        SingleNodeContainerSettings s = new SingleNodeContainerSettings();
        NodeContainerSettings ncSet = new NodeContainerSettings();
        ncSet.save(settings);
        s.save(settings);

    }

    /** {@inheritDoc} */
    @Override
    public NodeType getType() {
        return NodeType.Meta;
    }

    /** {@inheritDoc} */
    @Override
    public URL getIcon() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    void cleanup() {
        synchronized (m_workflowMutex) {
            super.cleanup();
            ReferencedFile ncDir = getNodeContainerDirectory();
            if (isProject() && ncDir != null) {
                ncDir.fileUnlockRootForVM();
            }
            // breadth first sorted list - traverse backwards (downstream before upstream nodes)
            final List<NodeID> idList = new ArrayList<NodeID>(
                    m_workflow.createBreadthFirstSortedList(m_workflow.getNodeIDs(), true).keySet());
            for (ListIterator<NodeID> reverseIt = idList.listIterator(idList.size()); reverseIt.hasPrevious();) {
                NodeContainer nc = getNodeContainer(reverseIt.previous());
                nc.cleanup();
            }
            if (m_tmpDir != null) {
                // delete the flow temp dir that we created
                KNIMEConstants.GLOBAL_THREAD_POOL.enqueue(new Runnable() {
                    @Override
                    public void run() {
                        if (m_tmpDir.isDirectory() && !FileUtil.deleteRecursively(m_tmpDir)) {
                            LOGGER.info("Could not delete temporary directory for workflow " + getName() + " at "
                                + m_tmpDir);
                        }
                    }
                });
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void setNodeContainerDirectory(final ReferencedFile directory) {
        ReferencedFile ncDir = getNodeContainerDirectory();
        if (ConvenienceMethods.areEqual(directory, ncDir)) {
            return;
        }
        final boolean isProject = isProject();
        if (isProject && ncDir != null) {
            ncDir.fileUnlockRootForVM();
        }
        if (isProject && !directory.fileLockRootForVM()) { // need to lock projects (but not meta nodes)
            throw new IllegalStateException("Workflow root directory \""
                    + directory + "\" can't be locked although it should have "
                    + "been locked by the save routines");
        }
        super.setNodeContainerDirectory(directory);
    }

    ///////////////////////////
    // Workflow port handling
    /////////////////////////

    /**
     * @return The number of incoming ports
     */
    public int getNrWorkflowIncomingPorts() {
        return getNrInPorts();
    }

    /**
     * @return The number of outgoing ports
     */
    public int getNrWorkflowOutgoingPorts() {
        return getNrOutPorts();
    }

    /**
     * @param i Index of the port
     * @return The incoming port at the given index
     */
    public NodeOutPort getWorkflowIncomingPort(final int i) {
        return m_inPorts[i].getUnderlyingPort();
    }

    /**
     * @param i Index of the port
     * @return The outgoing port at the given index
     */
    public NodeInPort getWorkflowOutgoingPort(final int i) {
        return m_outPorts[i].getSimulatedInPort();
    }

    /** Set UI information for workflow's input ports
     * (typically aligned as a bar).
     * @param inPortsBarUIInfo The new UI info.
     */
    public void setInPortsBarUIInfo(final UIInformation inPortsBarUIInfo) {
        if (!ConvenienceMethods.areEqual(m_inPortsBarUIInfo, inPortsBarUIInfo)) {
            m_inPortsBarUIInfo = inPortsBarUIInfo;
            setDirty();
        }
    }

    /** Set UI information for workflow's output ports
     * (typically aligned as a bar).
     * @param outPortsBarUIInfo The new UI info.
     */
    public void setOutPortsBarUIInfo(final UIInformation outPortsBarUIInfo) {
        if (!ConvenienceMethods.areEqual(
                m_outPortsBarUIInfo, outPortsBarUIInfo)) {
            m_outPortsBarUIInfo = outPortsBarUIInfo;
            setDirty();
        }
    }

    /** Get UI information for workflow input ports.
     * @return the ui info or null if not set.
     * @see #setInPortsBarUIInfo(UIInformation)
     */
    public UIInformation getInPortsBarUIInfo() {
        return m_inPortsBarUIInfo;
    }

    /** Get UI information for workflow output ports.
     * @return the ui info or null if not set.
     * @see #setOutPortsBarUIInfo(UIInformation)
     */
    public UIInformation getOutPortsBarUIInfo() {
        return m_outPortsBarUIInfo;
    }

    /////////////////////////////
    // Workflow Variable handling
    /////////////////////////////

    /* Private routine which assembles a stack of workflow variables all
     * the way to the top of the workflow hierarchy.
     */
    /**
     * {@inheritDoc}
     */
    @Override
    public void pushWorkflowVariablesOnStack(final FlowObjectStack sos) {
        if (getID().equals(ROOT.getID())) {
            // reach top of tree, return
            return;
        }
        // otherwise push variables of parent...
        getDirectNCParent().pushWorkflowVariablesOnStack(sos);
        // ... and then our own
        if (m_workflowVariables != null) {
            // if we have some vars, put them on stack
            for (FlowVariable sv : m_workflowVariables) {
                sos.push(sv.clone());
            }
        }
        return;
    }

    /** Get read-only access on the current workflow variables.
     * @return the current workflow variables, never null.
     */
    @SuppressWarnings("unchecked")
    public List<FlowVariable> getWorkflowVariables() {
        return m_workflowVariables == null ? Collections.EMPTY_LIST
                : Collections.unmodifiableList(m_workflowVariables);
    }

    /* @return stack of workflow variables. */
    private FlowObjectStack getWorkflowVariableStack() {
        // assemble new stack
        FlowObjectStack sos = new FlowObjectStack(getID());
        // push own variables and the ones of the parent(s):
        pushWorkflowVariablesOnStack(sos);
        return sos;
    }

    /** Set new workflow variables. All nodes within
     * this workflow will have access to these variables.
     * The method may change in future versions or removed entirely (bug 1937).
     *
     * @param newVars new variables to be set
     * @param skipReset if false the workflow will be re-configured
     */
    public void addWorkflowVariables(final boolean skipReset,
            final FlowVariable... newVars) {
        // meta node variables not supported for two reasons
        // (1) missing configure propagation and (2) meta-node variables need
        // to be hidden in outer workflow
        assert (getNrInPorts() == 0 && getNrOutPorts() == 0)
            : "Workflow variables can't be set on meta nodes";
        synchronized (m_workflowMutex) {
            if (m_workflowVariables == null) {
                // create new set of vars if none exists
                m_workflowVariables = new Vector<FlowVariable>();
            }
            for (FlowVariable sv : newVars) {
                // make sure old variables of the same name are removed first
                removeWorkflowVariable(sv.getName());
                m_workflowVariables.add(sv);
            }
            if (!skipReset) {
                // usually one needs to reset the Workflow to make sure the
                // new variable settings are used by all nodes!
                // Note that resetAll also needs to configure non-executed
                // nodes in order to spread those new variables correctly!
                resetAndReconfigureAllNodesInWFM();
            } else {
                // otherwise only configure already configured nodes. This
                // is required to make sure they rebuild their
                // FlowObjectStack!
                reconfigureAllNodesOnlyInThisWFM();
            }
            setDirty();
        }
    }

    /* -- Workflow Annotations ---------------------------------------------*/

    /** @return read-only collection of all currently registered annotations. */
    public Collection<WorkflowAnnotation> getWorkflowAnnotations() {
        return Collections.unmodifiableList(m_annotations);
    }

    /** Add new workflow annotation, fire events.
     * @param annotation to add
     * @throws IllegalArgumentException If annotation already registered. */
    /** Add new workflow annotation, fire events.
     * @param annotation to add
     * @throws IllegalArgumentException If annotation already registered. */
    public void addWorkflowAnnotation(final WorkflowAnnotation annotation) {
        addWorkflowAnnotationInternal(annotation);
        setDirty();
    }

    /** Adds annotation as in #addWorkf but does not fire dirty event. */
    private void addWorkflowAnnotationInternal(
            final WorkflowAnnotation annotation) {
        if (m_annotations.contains(annotation)) {
            throw new IllegalArgumentException("Annotation \"" + annotation
                    + "\" already exists");
        }
        m_annotations.add(annotation);
        annotation.addUIInformationListener(this);
        notifyWorkflowListeners(new WorkflowEvent(
                WorkflowEvent.Type.ANNOTATION_ADDED, null, null, annotation));
    }

    /** Remove workflow annotation, fire events.
     * @param annotation to remove
     * @throws IllegalArgumentException If annotation is not registered. */
    public void removeAnnotation(final WorkflowAnnotation annotation) {
        if (!m_annotations.remove(annotation)) {
            throw new IllegalArgumentException("Annotation \"" + annotation
                    + "\" does not exists");
        }
        annotation.removeUIInformationListener(this);
        notifyWorkflowListeners(new WorkflowEvent(
                WorkflowEvent.Type.ANNOTATION_REMOVED, null, annotation, null));
        setDirty();
    }

    /**
     * Resorts the internal array to move the specified annotation to the last index.
     * @param annotation to bring to front
     * @since 2.6
     */
    public void bringAnnotationToFront(final WorkflowAnnotation annotation) {
        if (!m_annotations.remove(annotation)) {
            throw new IllegalArgumentException("Annotation \"" + annotation
                    + "\" does not exists - can't be moved to front");
        }
        m_annotations.add(annotation);
        annotation.fireChangeEvent(); // triggers workflow dirty
    }

    /**
     * Resorts the internal array to move the specified annotation to the first index.
     * @param annotation to bring to front
     * @since 2.6
     */
    public void sendAnnotationToBack(final WorkflowAnnotation annotation) {
        if (!m_annotations.remove(annotation)) {
            throw new IllegalArgumentException("Annotation \"" + annotation
                    + "\" does not exists - can't be moved to front");
        }
        m_annotations.insertElementAt(annotation, 0);
        annotation.fireChangeEvent(); // triggers workflow dirty
    }

    /** Listener to annotations, etc; sets content dirty.
     * @param evt Change event. */
    @Override
    public void nodeUIInformationChanged(final NodeUIInformationEvent evt) {
        setDirty();
    }


    /* -------------------------------------------------------------------*/

    /* --------Node Annotations ------------------------------------------*/

    /**
     * @return a list of all node annotations in the contained flow.
     */
    public List<NodeAnnotation> getNodeAnnotations() {
        synchronized (m_workflowMutex) {
            Collection<NodeContainer> nodeContainers = getNodeContainers();
            List<NodeAnnotation> result = new LinkedList<NodeAnnotation>();
            for (NodeContainer node : nodeContainers) {
                result.add(node.getNodeAnnotation());
            }
            return result;
        }
    }

    /* -------------------------------------------------------------------*/

    /** A subclassable filter object that is used in various findXYZ methods. Can be used to fine-tune the search.
     * @param <T> the sub class or interface implemented by the node model.
     * @since 2.7
     */
    public static class NodeModelFilter<T> {

        /** Tests whether the concrete instance is to be included.
         * @param nodeModel Test instance, not null.
         * @return true if to include, false otherwise.
         */
        public boolean include(final T nodeModel) {
            return true;
        }
    }

    /**
     * Retrieves the node with the given ID, fetches the underlying
     * {@link NodeModel} and casts it to the argument class.
     * @param id The node of interest
     * @param cl The class object the underlying NodeModel needs to implement
     * @param <T> The type the class
     * @return The casted node model.
     * @throws IllegalArgumentException If the node does not exist, is not
     *         a {@link NativeNodeContainer} or the model does not implement the
     *         requested type.
     */
    public <T> T castNodeModel(final NodeID id, final Class<T> cl) {
        NodeContainer nc = getNodeContainer(id);
        if (!(nc instanceof NativeNodeContainer)) {
            throw new IllegalArgumentException("Node \"" + nc + "\" not a native node container");
        }
        NodeModel model = ((NativeNodeContainer)nc).getNodeModel();
        if (!cl.isInstance(model)) {
            throw new IllegalArgumentException("Node \"" + nc + "\" not instance of " + cl.getSimpleName());
        }
        return cl.cast(model);
    }

    /** Find all nodes in this workflow, whose underlying {@link NodeModel} is
     * of the requested type. Intended purpose is to allow certain extensions
     * (reporting, web service, ...) access to specialized nodes.
     * @param <T> Specific NodeModel derivation or another interface
     *            implemented by NodeModel instances.
     * @param nodeModelClass The class of interest
     * @param recurse Whether to recurse into contained meta nodes.
     * @return A (unsorted) list of nodes matching the class criterion
     */
    public <T> Map<NodeID, T> findNodes(final Class<T> nodeModelClass, final boolean recurse) {
        return findNodes(nodeModelClass, new NodeModelFilter<T>(), recurse);
    }

    /** Find all nodes in this workflow, whose underlying {@link NodeModel} is
     * of the requested type. Intended purpose is to allow certain extensions
     * (reporting, web service, ...) access to specialized nodes.
     * @param <T> Specific NodeModel derivation or another interface
     *            implemented by NodeModel instances.
     * @param nodeModelClass The class of interest
     * @param filter A non-null filter to apply.
     * @param recurse Whether to recurse into contained meta nodes.
     * @return A (unsorted) list of nodes matching the class criterion
     * @since 2.7
     */
    public <T> Map<NodeID, T> findNodes(final Class<T> nodeModelClass, final NodeModelFilter<T> filter,
                                        final boolean recurse) {
        synchronized (m_workflowMutex) {
            Map<NodeID, T> result = new LinkedHashMap<NodeID, T>();
            for (NodeContainer nc : m_workflow.getNodeValues()) {
                if (nc instanceof NativeNodeContainer) {
                    NativeNodeContainer nnc = (NativeNodeContainer)nc;
                    NodeModel model = nnc.getNode().getNodeModel();
                    boolean included = nodeModelClass.isAssignableFrom(model.getClass())
                            && filter.include(nodeModelClass.cast(model));
                    if (included) {
                        result.put(nnc.getID(), nodeModelClass.cast(model));
                    }
                }
            }
            if (recurse) { // do separately to maintain some sort of order
                for (NodeContainer nc : m_workflow.getNodeValues()) {
                    if (nc instanceof WorkflowManager) {
                        result.putAll(((WorkflowManager)nc).findNodes(nodeModelClass, true));
                    }
                }
            }
            return result;
        }
    }

    /** Get the node container associated with the argument id. Recurses into
     * contained meta nodes to find the node if it's not directly contained
     * in this workflow level.
     *
     * <p>Clients should generally use {@link #getNodeContainer(NodeID)} to
     * access directly contained nodes.
     *
     * @param id the id of the node in question
     * @return The node container to the node id (never null)
     * @throws IllegalArgumentException If the node is not contained in
     * this workflow.
     * @since 2.6 */
    public NodeContainer findNodeContainer(final NodeID id) {
        synchronized (m_workflowMutex) {
            final NodeID prefix = id.getPrefix();
            if (prefix.equals(getID())) {
                return getNodeContainer(id);
            } else if (id.hasPrefix(getID())) {
                NodeContainer parentNC = findNodeContainer(prefix);
                if (parentNC instanceof WorkflowManager) {
                    return ((WorkflowManager)parentNC).getNodeContainer(id);
                }
                if (parentNC instanceof SubNodeContainer) {
                    return ((SubNodeContainer)parentNC).getWorkflowManager();
                }
                throw new IllegalArgumentException("NodeID " + id + " is not contained in workflow "
                        + getNameWithID() + " - parent node is not meta node");
            } else {
                throw new IllegalArgumentException("NodeID " + id
                        + " is not contained in workflow " + getNameWithID());
            }
        }
    }

    /**
     * Find all nodes of a certain type that are currently ready to be executed (= node is configured, all predecessors
     * are executed). See {@link #findNodes(Class, boolean)}
     *
     * @param <T> ...
     * @param nodeModelClass ...
     * @param filter non null refinement filter
     * @return ...
     * @since 2.7
     * @noreference This method is not intended to be referenced by clients.
     */
    public <T> Map<NodeID, T> findWaitingNodes(final Class<T> nodeModelClass, final NodeModelFilter<T> filter) {
        synchronized (m_workflowMutex) {
            Map<NodeID, T> nodes = findNodes(nodeModelClass, /*recurse=*/false);
            Iterator<Map.Entry<NodeID, T>> it = nodes.entrySet().iterator();
            while (it.hasNext()) {
                Entry<NodeID, T> next = it.next();
                NodeID id = next.getKey();
                T nodeModel = next.getValue();
                NodeContainer nc = getNodeContainer(id);
                PortObject[] inData = new PortObject[nc.getNrInPorts()];
                if (!filter.include(nodeModel)) {
                    it.remove();
                } else if (InternalNodeContainerState.EXECUTED.equals(nc.getInternalState())
                    || (!assembleInputData(id, inData))) {
                    // only keep nodes that can be executed (= have all
                    // data available) but are not yet executed
                    it.remove();
                } else if (nc instanceof SingleNodeContainer) {
                    if (((SingleNodeContainer)nc).isInactive()) {
                        // also remove inactive nodes:
                        it.remove();
                    }
                }
            }
            return nodes;
        }
    }

    /** Find all nodes of a certain type that are already executed.
     * See {@link #findNodes(Class, boolean)}
     *
     * @param <T> ...
     * @param nodeModelClass ...
     * @param filter non null refinement filter
     * @return ...
     * @since 2.7
     * @noreference This method is not intended to be referenced by clients.
     */
    public <T> Map<NodeID, T> findExecutedNodes(final Class<T> nodeModelClass, final NodeModelFilter<T> filter) {
        synchronized (m_workflowMutex) {
            Map<NodeID, T> nodes = findNodes(nodeModelClass, /*recurse=*/false);
            Iterator<Map.Entry<NodeID, T>> it = nodes.entrySet().iterator();
            while (it.hasNext()) {
                Entry<NodeID, T> next = it.next();
                NodeID id = next.getKey();
                SingleNodeContainer nc = (SingleNodeContainer)getNodeContainer(id);
                if (!filter.include(next.getValue())) {
                    it.remove();
                } else if (!InternalNodeContainerState.EXECUTED.equals(nc.getInternalState())) {
                    it.remove();
                } else if (nc.isInactive()) {
                    it.remove();
                }
            }
            return nodes;
        }
    }

    /** Find "next" workflowmanager which contains nodes of a certain type
     * that are currently ready to be executed.
     * See {@link #findWaitingNodes(Class, NodeModelFilter)}
     *
     * @param <T> ...
     * @param nodeModelClass ...
     * @param filter A non-null filter.
     * @return Workflowmanager with waiting nodes or null if none exists.
     * @since 2.7
     */
    public <T> WorkflowManager findNextWaitingWorkflowManager(
            final Class<T> nodeModelClass, final NodeModelFilter<T> filter) {
        return findNextWaitingWorkflowManager(this, nodeModelClass, filter);
    }

    /**
     * recursion for nested meta nodes.
     */
    private <T> WorkflowManager findNextWaitingWorkflowManager(
            final WorkflowManager parentWfm,
            final Class<T> nodeModelClass, final NodeModelFilter<T> filter) {
        synchronized (m_workflowMutex) {
            Map<NodeID, T> nodes = parentWfm.findWaitingNodes(nodeModelClass, filter);
            if (nodes.size() > 0) {
                return this;
            }
            // search metanodes:
            Workflow workflow = parentWfm.getWorkflow();
            for (NodeID id : workflow.getNodeIDs()) {
                NodeContainer nc = workflow.getNode(id);
                if (nc.isLocalWFM()) {
                    WorkflowManager wfm = (WorkflowManager)nc;
                    Map<NodeID, T> n2s = wfm.findWaitingNodes(nodeModelClass, filter);
                    if (n2s.size() > 0) {
                        return wfm;
                    } else {
                        WorkflowManager nextWfm = findNextWaitingWorkflowManager(wfm, nodeModelClass, filter);
                        if (nextWfm != null) {
                            return nextWfm;
                        }
                    }
                }
            }
            // didn't find anything:
            return null;
        }
    }

    /** Get quickform nodes on the root level along with their currently set value. These are all
     * {@link org.knime.core.node.dialog.DialogNode} including special nodes like "JSON Input".
     *
     * <p>Method is used to allow clients to retrieve an example input.
     * @return A map from {@link DialogNode#getParameterName() node's parameter name} to its (JSON object value)
     * @since 2.12
     */
    public Map<String, JsonObject> getInputNodes() {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        synchronized (getWorkflowMutex()) {
            Map<NodeID, DialogNode> nodeMap = findNodes(DialogNode.class, false);
            for (Map.Entry<NodeID, DialogNode> e : nodeMap.entrySet()) {
                DialogNode dialogNode = e.getValue();
                String parameterName = StringUtils.defaultString(dialogNode.getParameterName());
                parameterName = (parameterName.isEmpty() ? "" : parameterName + "-")
                        + Integer.toString(e.getKey().getIndex());
                DialogNodeValue dialogValue = dialogNode.getDefaultValue();
                JsonObject jsonObject = dialogValue != null ? dialogValue.toJson() : null;
                if (jsonObject == null) {
                    jsonObject = Json.createObjectBuilder().build();
                }
                result.put(parameterName, jsonObject);
            }
            return result;
        }
    }

    /** Counterpart to {@link #getInputNodes()} - it sets new values into quickform nodes on the root level.
     * All nodes as per map argument will be reset as part of this call.
     * @param input a map from {@link org.knime.core.node.dialog.DialogNode#getParameterName() node's parameter name}
     * to its (JSON object value). Invalid entries cause an exception.
     * @since 2.12
     */
    public void setInputNodes(final Map<String, JsonObject> input) {
        synchronized (getWorkflowMutex()) {
            CheckUtils.checkState(!getNodeContainerState().isExecutionInProgress(),
                "Cannot apply new parameters - workflow still in execution");
            // splits "foobar-123-xy-2" into "foobar-123-xy" (parameter name) and 2 (node id suffix)
            Pattern p = Pattern.compile("^(?:(.+)-)?(\\d+)$");
            Map<NodeID, DialogNode> nodeMap = findNodes(DialogNode.class, false);
            Map<NodeID, DialogNodeValue> valueMap = new LinkedHashMap<>();
            for (Map.Entry<String, JsonObject> entry : input.entrySet()) {
                String parameterName = entry.getKey();
                Matcher parameterNameMatcher = p.matcher(parameterName);
                CheckUtils.checkArgument(parameterNameMatcher.matches(),
                    "Invalid parameter name \"%s\" (invalid pattern)", parameterName);
                String paramBase = StringUtils.defaultString(parameterNameMatcher.group(1));
                Integer idSuffix = Integer.parseInt(parameterNameMatcher.group(2));
                NodeID id = new NodeID(getID(), idSuffix);
                DialogNode dialogNode = nodeMap.get(id);
                CheckUtils.checkArgument(dialogNode != null && paramBase.equals(StringUtils.defaultString(
                    dialogNode.getParameterName())),
                    "Invalid parameter name \"%s\" - could not locate corresponding node in workflow", parameterName);
                DialogNodeValue dialogValue = dialogNode.createEmptyDialogValue();
                try {
                    dialogValue.loadFromJson(entry.getValue());
                    dialogNode.validateDialogValue(dialogValue);
                } catch (JsonException e) {
                    throw new IllegalArgumentException("Invalid JSON parameter for node \"" + parameterName
                        + "\" - node doesn't understand JSON: " + e.getMessage(), e);
                } catch (InvalidSettingsException se) {
                    throw new IllegalArgumentException("Invalid parameter for node \"" + parameterName
                        + "\" - didn't pass node's validation method: " + se.getMessage(), se);
                }
                valueMap.put(id, dialogValue);
            }

            for (Map.Entry<NodeID, DialogNodeValue> entry : valueMap.entrySet()) {
                NativeNodeContainer nnc = getNodeContainer(entry.getKey(), NativeNodeContainer.class, true);
                final DialogNode dialogNode = (DialogNode)nnc.getNodeModel();
                dialogNode.setDialogValue(entry.getValue());
                LOGGER.debugWithFormat("Setting new parameter for node \"%s\" (%s)",
                    nnc.getNameWithID(), dialogNode.getParameterName());
                resetAndConfigureNode(entry.getKey());
            }
        }
    }

    /** Receive output from workflow by means of {@link org.knime.core.node.dialog.JSONOutputNode}. Calling
     * this method on a non-fully executed workflow causes an exception to be thrown.
     * @return A map from node's parameter name to its JSON object result.
     * @since 2.12
     */
    public Map<String, JsonObject> getOutputNodes() {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        synchronized (getWorkflowMutex()) {
            CheckUtils.checkState(getNodeContainerState().isExecuted(), "Workflow not completely executed");
            Map<NodeID, JSONOutputNode> nodeMap = findNodes(JSONOutputNode.class, false);
            for (Map.Entry<NodeID, JSONOutputNode> e : nodeMap.entrySet()) {
                JSONOutputNode jsonOutNode = e.getValue();
                String parameterName = StringUtils.defaultString(jsonOutNode.getParameterName());
                parameterName = (parameterName.isEmpty() ? "" : (parameterName + "-"))
                        + Integer.toString(e.getKey().getIndex());
                JsonObject jsonObject = jsonOutNode.getJSONObject();
                if (jsonObject == null) {
                    jsonObject = Json.createObjectBuilder().build();
                }
                result.put(parameterName, jsonObject);
            }
            return result;
        }
    }

    /** Remove workflow variable of given name.
     * The method may change in future versions or removed entirely (bug 1937).
     *
     * @param name of variable to be removed.
     */
    public void removeWorkflowVariable(final String name) {
        for (int i = 0; i < m_workflowVariables.size(); i++) {
            FlowVariable sv = m_workflowVariables.elementAt(i);
            if (sv.getName().equals(name)) {
                m_workflowVariables.remove(i);
            }
        }
    }

    /**
     * @param id of node
     * @return set of NodeGraphAnnotations for this node (can be more than one for Metanodes).
     * @since 2.8
     */
    public Set<NodeGraphAnnotation> getNodeGraphAnnotation(final NodeID id) {
        return m_workflow.getNodeGraphAnnotations(id);
    }

    /**
     * Returns the current workflow context or <code>null</code> if no context is available.
     *
     * @return a workflow context or <code>null</code>
     * @since 2.8
     */
    public WorkflowContext getContext() {
        return m_workflowContext;
    }

    /** Meta data such as who create the workflow and who edited it last and when.
     * @since 2.8 */
    public static final class AuthorInformation {

        /** Info for workflows created prior 2.8. */
        static final AuthorInformation UNKNOWN = new AuthorInformation("<unknown>", new Date(0), null, null);

        private final String m_author;
        private final Date m_authoredDate;
        private final String m_lastEditor;
        private final Date m_lastEditDate;

        private AuthorInformation() {
            this (System.getProperty("user.name"), new Date(), null, null);
        }

        private AuthorInformation(final AuthorInformation past) {
            this (past.m_author, past.m_authoredDate, System.getProperty("user.name"), new Date());
        }

        /**
         * @param author Original author.
         * @param authoredDate Original authored date.
         * @param lastEditor Name of last editor.
         * @param lastEditDate Date of last edit.
         */
        AuthorInformation(final String author, final Date authoredDate,
            final String lastEditor, final Date lastEditDate) {
            m_author = author;
            m_authoredDate = authoredDate;
            m_lastEditor = lastEditor;
            m_lastEditDate = lastEditDate;
        }

        /** @return Name of the workflow author (person). Null when not saved yet. */
        public String getAuthor() {
            return m_author;
        }

        /** @return Date when the workflow was saved the first time. Can be null. */
        public Date getAuthoredDate() {
            return m_authoredDate;
        }

        /** @return Name of the person who edited the workflow last (on last save). Null when not saved yet. */
        public String getLastEditor() {
            return m_lastEditor;
        }

        /** @return Date when workflow was saved last. */
        public Date getLastEditDate() {
            return m_lastEditDate;
        }
    }

    /**
     * {@inheritDoc}
     * @since 2.10
     */
    @Override
    public void notifyTemplateConnectionChangedListener() {
        notifyNodePropertyChangedListener(NodeProperty.TemplateConnection);
    }

}
