/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.internal.data;

import org.glassfish.api.container.Sniffer;
import org.glassfish.api.container.Container;
import org.glassfish.api.deployment.*;
import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.ParameterNames;
import org.glassfish.internal.deployment.ExtendedDeploymentContext;
import org.jvnet.hk2.component.PreDestroy;
import org.jvnet.hk2.config.TransactionFailure;
import org.jvnet.hk2.config.ConfigSupport;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.lang.instrument.ClassFileTransformer;
import java.beans.PropertyVetoException;

import com.sun.logging.LogDomains;
import com.sun.enterprise.config.serverbeans.Application;
import com.sun.enterprise.config.serverbeans.Module;
import com.sun.enterprise.config.serverbeans.Engine;

/**
 * Each module of an application has an associated module info instance keeping
 * the list of engines in which that module is loaded.
 *
 * @author Jerome Dochez
 */
public class ModuleInfo {

    final static private Logger logger = LogDomains.getLogger(ApplicationInfo.class, LogDomains.CORE_LOGGER);
    
    private final Set<EngineRef> engines = new LinkedHashSet<EngineRef>();
    private final String name;

    public ModuleInfo(String name, Collection<EngineRef> refs) {
        this.name = name;
        for (EngineRef ref : refs) {
            engines.add(ref);
        }
    }

    public Set<EngineRef> getEngineRefs() {
        Set<EngineRef> copy = new LinkedHashSet<EngineRef>();
        copy.addAll(_getEngineRefs());
        return copy; 
    }

    protected Set<EngineRef> _getEngineRefs() {
        return engines;
    }

    public String getName() {
        return name;
    }


    /**
     * Returns the list of sniffers that participated in loaded this
     * application
     *
     * @return array of sniffer that loaded the application's module
     */
    public Collection<Sniffer> getSniffers() {
        List<Sniffer> sniffers = new ArrayList<Sniffer>();
        for (EngineRef engine : _getEngineRefs()) {
            sniffers.add(engine.getContainerInfo().getSniffer());
        }
        return sniffers;
    }

    public void load(ExtendedDeploymentContext context, ActionReport report, ProgressTracker tracker) throws Exception {

        context.setPhase(ExtendedDeploymentContext.Phase.LOAD);

        if (!context.getTransformers().isEmpty()) {
            // add the class file transformers to the new class loader
            try {
                InstrumentableClassLoader icl = InstrumentableClassLoader.class.cast(context.getFinalClassLoader());
                for (ClassFileTransformer transformer : context.getTransformers()) {
                    icl.addTransformer(transformer);
                }
            } catch (Exception e) {
                report.failure(logger, "Class loader used for loading application cannot handle bytecode enhancer", e);
                throw e;
            }
        }
        for (EngineRef engine : _getEngineRefs()) {

            final EngineInfo engineInfo = engine.getContainerInfo();

            // get the container.
            Deployer deployer = engineInfo.getDeployer();

            ClassLoader currentClassLoader  = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(context.getClassLoader());
                ApplicationContainer appCtr = deployer.load(engineInfo.getContainer(), context);
                if (appCtr==null) {
                    String msg = "Cannot load application in " + engineInfo.getContainer().getName() + " container";
                    report.failure(logger, msg, null);
                    throw new Exception(msg);
                }
                tracker.add("loaded", EngineRef.class, engine);
                engine.load(context);
                engine.setApplicationContainer(appCtr);


            } catch(Exception e) {
                report.failure(logger, "Exception while invoking " + deployer.getClass() + " prepare method", e);
                throw e;
            } finally {
                Thread.currentThread().setContextClassLoader(currentClassLoader);
            }
        }
    }    

    /*
     * Returns the EngineRef for a particular container type
     * @param type the container type
     * @return the module info is this application as a module implemented with
     * the passed container type
     */
    public <T extends Container> EngineRef getEngineRefForContainer(Class<T> type) {
        for (EngineRef engine : _getEngineRefs()) {
            T container = null;
            try {
                container = type.cast(engine.getContainerInfo().getContainer());
            } catch (Exception e) {
                // ignore, wrong container
            }
            if (container!=null) {
                return engine;
            }
        }
        return null;
    }


    public void start(
        DeploymentContext context,
        ActionReport report, ProgressTracker tracker) throws Exception {

        // registers all deployed items.
        for (EngineRef engine : _getEngineRefs()) {

            try {
                if (!engine.start( context, tracker)) {
                    report.failure(logger, "Module not started " +  engine.getApplicationContainer().toString());
                    throw new Exception( "Module not started " +  engine.getApplicationContainer().toString());
                }
            } catch(Exception e) {
                report.failure(logger, "Exception while invoking " + engine.getApplicationContainer().getClass() + " start method", e);
                throw e;
            }
        }
    }

    public void stop(ApplicationContext context, Logger logger) {

        for (EngineRef module : _getEngineRefs()) {
            try {
                module.stop(context, logger);
            } catch(Exception e) {
                logger.log(Level.SEVERE, "Cannot stop module " +
                        module.getContainerInfo().getSniffer().getModuleType(),e );
            }
        }
    }

    public void unload(ExtendedDeploymentContext context, ActionReport report) {

        stop(context, logger);

        Set<ClassLoader> classLoaders = new HashSet<ClassLoader>();
        for (EngineRef engine : _getEngineRefs()) {
            if (engine.getApplicationContainer()!=null && engine.getApplicationContainer().getClassLoader()!=null) {
                classLoaders.add(engine.getApplicationContainer().getClassLoader());
            }
            try {
                engine.unload(context, report);
            } catch(Throwable e) {
                logger.log(Level.SEVERE, "Failed to unload from container type : " +
                        engine.getContainerInfo().getSniffer().getModuleType(), e);
            }
        }
        // all modules have been unloaded, clean the class loaders...
        for (ClassLoader cloader : classLoaders) {
            try {
                PreDestroy.class.cast(cloader).preDestroy();
            } catch (Exception e) {
                // ignore, the class loader does not need to be explicitely stopped.
            }
        }        
    }

    public void clean(ExtendedDeploymentContext context) throws Exception {
        
        for (EngineRef ref : _getEngineRefs()) {
            ref.clean(context, logger);
        }
        
    }

    public boolean suspend(Logger logger) {

        boolean isSuccess = true;

        for (EngineRef engine : _getEngineRefs()) {
            try {
                engine.getApplicationContainer().suspend();
            } catch(Exception e) {
                isSuccess = false;
                logger.log(Level.SEVERE, "Error suspending module " +
                           engine.getContainerInfo().getSniffer().getModuleType(),e );
            }
        }

        return isSuccess;
    }

    public boolean resume(Logger logger) {

        boolean isSuccess = true;

        for (EngineRef module : _getEngineRefs()) {
            try {
                module.getApplicationContainer().resume();
            } catch(Exception e) {
                isSuccess = false;
                logger.log(Level.SEVERE, "Error resuming module " +
                           module.getContainerInfo().getSniffer().getModuleType(),e );
            }
        }

        return isSuccess;
    }

    /**
     * Saves its state to the configuration. this method must be called within a transaction
     * to the configured module instance.
     *
     * @param module the module being persisted
     */
    public void save(Module module) throws TransactionFailure, PropertyVetoException {

        module.setName(name);
        for (EngineRef ref : _getEngineRefs()) {
            Engine engine = module.createChild(Engine.class);
            module.getEngines().add(engine);
            ref.save(engine);
        }
    }
}
