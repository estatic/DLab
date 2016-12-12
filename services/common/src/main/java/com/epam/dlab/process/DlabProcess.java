package com.epam.dlab.process;/*
Copyright 2016 EPAM Systems, Inc.
 
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
 
    http://www.apache.org/licenses/LICENSE-2.0
 
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

import com.aegisql.conveyor.cart.command.RescheduleCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DlabProcess {

    private final static Logger LOG = LoggerFactory.getLogger(DlabProcess.class);

    private final static DlabProcess INSTANCE = new DlabProcess();

    private ExecutorService executorService = Executors.newFixedThreadPool(3*50);

    public static DlabProcess getInstance() {
        return INSTANCE;
    }

    private final ProcessConveyor processConveyor;

    private DlabProcess() {
        this.processConveyor = new ProcessConveyor();
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public void setExecutorServiceMaxParallelism(int parallelism) {
        this.executorService.shutdown();
        this.executorService = Executors.newFixedThreadPool(3*parallelism);
    }

    public CompletableFuture<ProcessInfo> start(ProcessId id, String command){
        CompletableFuture<ProcessInfo> future = processConveyor.createBuildFuture( id, ()-> new ProcessInfoBuilder(id) );
        try {
            processConveyor.add(id, command, ProcessStep.START);
        } catch (DlabProcessException e){
            LOG.debug("Rescheduling {} {} {}",id,command,e.getMessage());
            RescheduleCommand<ProcessId> reschedule = new RescheduleCommand<>(id, 1, TimeUnit.MINUTES);
            processConveyor.addCommand(reschedule);
            processConveyor.add(id, command, ProcessStep.SCHEDULE);
        }
        return future;
    }

    public CompletableFuture<Boolean> stop(ProcessId id){
        return processConveyor.add(id,"STOP",ProcessStep.STOP);
    }

    public CompletableFuture<Boolean> kill(ProcessId id){
        return processConveyor.add(id,"KILL",ProcessStep.KILL);
    }

    public CompletableFuture<Boolean> failed(ProcessId id){
        return processConveyor.add(id,"FAILED",ProcessStep.FAILED);
    }

    public CompletableFuture<Boolean> finish(ProcessId id, Integer exitStatus){
        return processConveyor.add(id,exitStatus,ProcessStep.FINISH);
    }

    public CompletableFuture<Boolean> toStdOut(ProcessId id, String msg){
        return processConveyor.add(id,msg,ProcessStep.STD_OUT);
    }

    public CompletableFuture<Boolean> toStdErr(ProcessId id, String msg){
        return processConveyor.add(id,msg,ProcessStep.STD_ERR);
    }

    public CompletableFuture<Boolean> toStdErr(ProcessId id, String msg, Throwable err){
        StringWriter sw = new StringWriter();
        sw.append(msg);
        sw.append("\n");
        PrintWriter pw = new PrintWriter(sw);
        err.printStackTrace(pw);
        return processConveyor.add(id,sw.toString(),ProcessStep.STD_ERR);
    }

    public Collection<ProcessId> getActiveProcesses() {
        Collection<ProcessId> pList = new ArrayList<>();
        processConveyor.forEachKeyAndBuilder( (k,b)-> pList.add(k) );
        return pList;
    }

    public void setProcessTimeout(long time, TimeUnit unit) {
        processConveyor.setDefaultBuilderTimeout(time,unit);
    }

    public void setMaxUserProcesses(int max) {
        processConveyor.setMaxUserProcesses(max);
    }

}
