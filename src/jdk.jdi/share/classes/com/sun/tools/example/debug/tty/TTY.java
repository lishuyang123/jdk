/*
 * Copyright (c) 1998, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * This source code is provided to illustrate the usage of a given feature
 * or technique and has been deliberately simplified. Additional steps
 * required for a production-quality application, such as security checks,
 * input validation and proper error handling, might not be present in
 * this sample code.
 */


package com.sun.tools.example.debug.tty;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import com.sun.jdi.connect.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.*;

public class TTY implements EventNotifier {
    /**
     * Commands that are repeatable on empty input.
     */
    protected static final Set<String> REPEATABLE = Set.of(
        "up", "down", "step", "stepi", "next", "cont", "list", "pop", "reenter"
    );

    /**
     * Commands that reset the default source line to be displayed by {@code list}.
     */
    protected static final Set<String> LIST_RESET = Set.of(
        "run", "suspend", "resume", "up", "down", "kill", "interrupt", "threadgroup", "step", "stepi", "next", "cont",
        "pop", "reenter"
    );

    EventHandler handler = null;

    /**
     * List of Strings to execute at each stop.
     */
    private List<String> monitorCommands = new CopyOnWriteArrayList<>();
    private int monitorCount = 0;

    /**
     * The name of this tool.
     */
    private static final String progname = "jdb";

    private volatile boolean shuttingDown = false;

    /**
     * The number of the next source line to target for {@code list}, if any.
     */
    protected Integer nextListTarget = null;

    /**
     * Whether to repeat when the user enters an empty command.
     */
    protected boolean repeat = false;

    public void setShuttingDown(boolean s) {
       shuttingDown = s;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    @Override
    public void vmStartEvent(VMStartEvent se)  {
        Thread.yield();  // fetch output
        MessageOutput.lnprint("VM Started:");
    }

    @Override
    public void vmDeathEvent(VMDeathEvent e)  {
    }

    @Override
    public void vmDisconnectEvent(VMDisconnectEvent e)  {
    }

    @Override
    public void threadStartEvent(ThreadStartEvent e)  {
    }

    @Override
    public void threadDeathEvent(ThreadDeathEvent e)  {
    }

    @Override
    public void classPrepareEvent(ClassPrepareEvent e)  {
    }

    @Override
    public void classUnloadEvent(ClassUnloadEvent e)  {
    }

    @Override
    public void breakpointEvent(BreakpointEvent be)  {
        Thread.yield();  // fetch output
        MessageOutput.lnprint("Breakpoint hit:");
        // Print breakpoint location and prompt if suspend policy is
        // SUSPEND_NONE or SUSPEND_EVENT_THREAD. In case of SUSPEND_ALL
        // policy this is handled by vmInterrupted() method.
        int suspendPolicy = be.request().suspendPolicy();
        switch (suspendPolicy) {
            case EventRequest.SUSPEND_EVENT_THREAD:
            case EventRequest.SUSPEND_NONE:
                printBreakpointLocation(be);
                MessageOutput.printPrompt();
                break;
        }
    }

    @Override
    public void fieldWatchEvent(WatchpointEvent fwe)  {
        Field field = fwe.field();
        ObjectReference obj = fwe.object();
        Thread.yield();  // fetch output

        if (fwe instanceof ModificationWatchpointEvent) {
            MessageOutput.lnprint("Field access encountered before after",
                                  new Object [] {field,
                                                 fwe.valueCurrent(),
                                                 ((ModificationWatchpointEvent)fwe).valueToBe()});
        } else {
            MessageOutput.lnprint("Field access encountered", field.toString());
        }
    }

    @Override
    public void stepEvent(StepEvent se)  {
        Thread.yield();  // fetch output
        MessageOutput.lnprint("Step completed:");
    }

    @Override
    public void exceptionEvent(ExceptionEvent ee) {
        Thread.yield();  // fetch output
        Location catchLocation = ee.catchLocation();
        if (catchLocation == null) {
            MessageOutput.lnprint("Exception occurred uncaught",
                                  ee.exception().referenceType().name());
        } else {
            MessageOutput.lnprint("Exception occurred caught",
                                  new Object [] {ee.exception().referenceType().name(),
                                                 Commands.locationString(catchLocation)});
        }
    }

    @Override
    public void methodEntryEvent(MethodEntryEvent me) {
        Thread.yield();  // fetch output
        /*
         * These can be very numerous, so be as efficient as possible.
         * If we are stopping here, then we will see the normal location
         * info printed.
         */
        if (me.request().suspendPolicy() != EventRequest.SUSPEND_NONE) {
            // We are stopping; the name will be shown by the normal mechanism
            MessageOutput.lnprint("Method entered:");
        } else {
            // We aren't stopping, show the name
            MessageOutput.print("Method entered:");
            printLocationOfEvent(me);
        }
    }

    @Override
    public boolean methodExitEvent(MethodExitEvent me) {
        Thread.yield();  // fetch output
        /*
         * These can be very numerous, so be as efficient as possible.
         */
        Method mmm = Env.atExitMethod();
        Method meMethod = me.method();

        if (mmm == null || mmm.equals(meMethod)) {
            // Either we are not tracing a specific method, or we are
            // and we are exitting that method.

            if (me.request().suspendPolicy() != EventRequest.SUSPEND_NONE) {
                // We will be stopping here, so do a newline
                MessageOutput.println();
            }
            if (Env.vm().canGetMethodReturnValues()) {
                MessageOutput.print("Method exitedValue:", me.returnValue() + "");
            } else {
                MessageOutput.print("Method exited:");
            }

            if (me.request().suspendPolicy() == EventRequest.SUSPEND_NONE) {
                // We won't be stopping here, so show the method name
                printLocationOfEvent(me);

            }

            // In case we want to have a one shot trace exit some day, this
            // code disables the request so we don't hit it again.
            if (false) {
                // This is a one shot deal; we don't want to stop
                // here the next time.
                Env.setAtExitMethod(null);
                EventRequestManager erm = Env.vm().eventRequestManager();
                for (EventRequest eReq : erm.methodExitRequests()) {
                    if (eReq.equals(me.request())) {
                        eReq.disable();
                    }
                }
            }
            return true;
        }

        // We are tracing a specific method, and this isn't it.  Keep going.
        return false;
    }

    @Override
    public void vmInterrupted() {
        Thread.yield();  // fetch output
        printCurrentLocation();
        for (String cmd : monitorCommands) {
            StringTokenizer t = new StringTokenizer(cmd);
            t.nextToken();  // get rid of monitor number
            executeCommand(t);
        }
        MessageOutput.printPrompt();
    }

    @Override
    public void receivedEvent(Event event) {
    }

    private void printBaseLocation(String threadName, Location loc) {
        MessageOutput.println("location",
                              new Object [] {threadName,
                                             Commands.locationString(loc)});
    }

    private void printBreakpointLocation(BreakpointEvent be) {
        printLocationWithSourceLine(be.thread().name(), be.location());
    }

    private void printCurrentLocation() {
        ThreadInfo threadInfo = ThreadInfo.getCurrentThreadInfo();
        StackFrame frame;
        try {
            frame = threadInfo.getCurrentFrame();
        } catch (IncompatibleThreadStateException exc) {
            MessageOutput.println("<location unavailable>");
            return;
        }
        if (frame == null) {
            MessageOutput.println("No frames on the current call stack");
        } else {
            printLocationWithSourceLine(threadInfo.getThread().name(), frame.location());
        }
        MessageOutput.println();
    }

    private void printLocationWithSourceLine(String threadName, Location loc) {
        printBaseLocation(threadName, loc);
        // Output the current source line, if possible
        if (loc.lineNumber() != -1) {
            String line;
            try {
                line = Env.sourceLine(loc, loc.lineNumber());
            } catch (java.io.IOException e) {
                line = null;
            }
            if (line != null) {
                MessageOutput.println("source line number and line",
                                           new Object [] {loc.lineNumber(),
                                                          line});
            }
        }
    }

    private void printLocationOfEvent(LocatableEvent theEvent) {
        printBaseLocation(theEvent.thread().name(), theEvent.location());
    }

    void help() {
        MessageOutput.println("zz help text");
    }

    private static final String[][] commandList = {
        /*
         * NOTE: this list must be kept sorted in ascending ASCII
         *       order by element [0].  Ref: isCommand() below.
         *
         *Command      OK when        OK when
         * name      disconnected?   readonly?
         *------------------------------------
         */
        {"!!",           "n",         "y"},
        {"?",            "y",         "y"},
        {"bytecodes",    "n",         "y"},
        {"catch",        "y",         "n"},
        {"class",        "n",         "y"},
        {"classes",      "n",         "y"},
        {"classpath",    "n",         "y"},
        {"clear",        "y",         "n"},
        {"connectors",   "y",         "y"},
        {"cont",         "n",         "n"},
        {"dbgtrace",     "y",         "y"},
        {"disablegc",    "n",         "n"},
        {"down",         "n",         "y"},
        {"dump",         "n",         "y"},
        {"enablegc",     "n",         "n"},
        {"eval",         "n",         "y"},
        {"exclude",      "y",         "n"},
        {"exit",         "y",         "y"},
        {"extension",    "n",         "y"},
        {"fields",       "n",         "y"},
        {"gc",           "n",         "n"},
        {"help",         "y",         "y"},
        {"ignore",       "y",         "n"},
        {"interrupt",    "n",         "n"},
        {"kill",         "n",         "n"},
        {"lines",        "n",         "y"},
        {"list",         "n",         "y"},
        {"load",         "n",         "y"},
        {"locals",       "n",         "y"},
        {"lock",         "n",         "n"},
        {"memory",       "n",         "y"},
        {"methods",      "n",         "y"},
        {"monitor",      "n",         "n"},
        {"next",         "n",         "n"},
        {"pop",          "n",         "n"},
        {"print",        "n",         "y"},
        {"quit",         "y",         "y"},
        {"read",         "y",         "y"},
        {"redefine",     "n",         "n"},
        {"reenter",      "n",         "n"},
        {"repeat",       "y",         "y"},
        {"resume",       "n",         "n"},
        {"run",          "y",         "n"},
        {"save",         "n",         "n"},
        {"set",          "n",         "n"},
        {"sourcepath",   "y",         "y"},
        {"step",         "n",         "n"},
        {"stepi",        "n",         "n"},
        {"stop",         "y",         "n"},
        {"suspend",      "n",         "n"},
        {"thread",       "n",         "y"},
        {"threadgroup",  "n",         "y"},
        {"threadgroups", "n",         "y"},
        {"threadlocks",  "n",         "y"},
        {"threads",      "n",         "y"},
        {"trace",        "n",         "n"},
        {"unmonitor",    "n",         "n"},
        {"untrace",      "n",         "n"},
        {"unwatch",      "y",         "n"},
        {"up",           "n",         "y"},
        {"use",          "y",         "y"},
        {"version",      "y",         "y"},
        {"watch",        "y",         "n"},
        {"where",        "n",         "y"},
        {"wherei",       "n",         "y"},
    };

    /*
     * Look up the command string in commandList.
     * If found, return the index.
     * If not found, return index < 0
     */
    private int isCommand(String key) {
        //Reference: binarySearch() in java/util/Arrays.java
        //           Adapted for use with String[][0].
        int low = 0;
        int high = commandList.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            String midVal = commandList[mid][0];
            int compare = midVal.compareTo(key);
            if (compare < 0) {
                low = mid + 1;
            } else if (compare > 0) {
                high = mid - 1;
            }
            else {
                return mid; // key found
        }
        }
        return -(low + 1);  // key not found.
    };

    /*
     * Return true if the command is OK when disconnected.
     */
    private boolean isDisconnectCmd(int ii) {
        if (ii < 0 || ii >= commandList.length) {
            return false;
        }
        return (commandList[ii][1].equals("y"));
    }

    /*
     * Return true if the command is OK when readonly.
     */
    private boolean isReadOnlyCmd(int ii) {
        if (ii < 0 || ii >= commandList.length) {
            return false;
        }
        return (commandList[ii][2].equals("y"));
    };


    /**
     * @return the name (first token) of the command processed
     */
    String executeCommand(StringTokenizer t) {
        String name = t.nextToken().toLowerCase();

        // Normally, prompt for the next command after this one is done
        boolean showPrompt = true;

        /*
         * Anything starting with # is discarded as a no-op or 'comment'.
         */
        if (!name.startsWith("#")) {
            /*
             * Next check for an integer repetition prefix.
             */
            boolean valid = true;
            int reps = 1;
            while (Character.isDigit(name.charAt(0)) && t.hasMoreTokens()) {
                try {
                    reps *= Integer.parseInt(name);  // nested repeats are possible
                } catch (NumberFormatException exc) {
                    valid = false;
                    MessageOutput.println("Unrecognized command.  Try help...", name);
                    break;
                }
                name = t.nextToken().toLowerCase();
            }
            if (valid) {
                int commandNumber = isCommand(name);
                String argsLine = t.hasMoreTokens() ? t.nextToken("") : "";
                for (int r = 0; r < reps; r += 1) {
                    /*
                     * Check for an unknown command
                     */
                    if (commandNumber < 0) {
                        MessageOutput.println("Unrecognized command.  Try help...", name);
                    } else if (!Env.connection().isOpen() && !isDisconnectCmd(commandNumber)) {
                        MessageOutput.println("Command not valid until the VM is started with the run command",
                                              name);
                    } else if (Env.connection().isOpen() && !Env.vm().canBeModified() &&
                               !isReadOnlyCmd(commandNumber)) {
                        MessageOutput.println("Command is not supported on a read-only VM connection",
                                              name);
                    } else {
                        Commands evaluator = new Commands();
                        var args = new StringTokenizer(argsLine);
                        try {
                            if (name.equals("print")) {
                                evaluator.commandPrint(args, false);
                                showPrompt = false;        // asynchronous command
                            } else if (name.equals("eval")) {
                                evaluator.commandPrint(args, false);
                                showPrompt = false;        // asynchronous command
                            } else if (name.equals("set")) {
                                evaluator.commandSet(args);
                                showPrompt = false;        // asynchronous command
                            } else if (name.equals("dump")) {
                                evaluator.commandPrint(args, true);
                                showPrompt = false;        // asynchronous command
                            } else if (name.equals("locals")) {
                                evaluator.commandLocals();
                            } else if (name.equals("classes")) {
                                evaluator.commandClasses();
                            } else if (name.equals("class")) {
                                evaluator.commandClass(args);
                            } else if (name.equals("connectors")) {
                                evaluator.commandConnectors(Bootstrap.virtualMachineManager());
                            } else if (name.equals("methods")) {
                                evaluator.commandMethods(args);
                            } else if (name.equals("fields")) {
                                evaluator.commandFields(args);
                            } else if (name.equals("threads")) {
                                evaluator.commandThreads(args);
                            } else if (name.equals("thread")) {
                                evaluator.commandThread(args);
                            } else if (name.equals("suspend")) {
                                evaluator.commandSuspend(args);
                            } else if (name.equals("resume")) {
                                evaluator.commandResume(args);
                            } else if (name.equals("cont")) {
                                MessageOutput.printPrompt(true);
                                showPrompt = false;
                                evaluator.commandCont();
                            } else if (name.equals("threadgroups")) {
                                evaluator.commandThreadGroups();
                            } else if (name.equals("threadgroup")) {
                                evaluator.commandThreadGroup(args);
                            } else if (name.equals("catch")) {
                                evaluator.commandCatchException(args);
                            } else if (name.equals("ignore")) {
                                evaluator.commandIgnoreException(args);
                            } else if (name.equals("step")) {
                                MessageOutput.printPrompt(true);
                                showPrompt = false;
                                evaluator.commandStep(args);
                            } else if (name.equals("stepi")) {
                                MessageOutput.printPrompt(true);
                                showPrompt = false;
                                evaluator.commandStepi();
                            } else if (name.equals("next")) {
                                MessageOutput.printPrompt(true);
                                showPrompt = false;
                                evaluator.commandNext();
                            } else if (name.equals("kill")) {
                                showPrompt = false;        // asynchronous command
                                evaluator.commandKill(args);
                            } else if (name.equals("interrupt")) {
                                evaluator.commandInterrupt(args);
                            } else if (name.equals("trace")) {
                                evaluator.commandTrace(args);
                            } else if (name.equals("untrace")) {
                                evaluator.commandUntrace(args);
                            } else if (name.equals("where")) {
                                evaluator.commandWhere(args, false);
                            } else if (name.equals("wherei")) {
                                evaluator.commandWhere(args, true);
                            } else if (name.equals("up")) {
                                evaluator.commandUp(args);
                            } else if (name.equals("down")) {
                                evaluator.commandDown(args);
                            } else if (name.equals("load")) {
                                evaluator.commandLoad(args);
                            } else if (name.equals("run")) {
                                evaluator.commandRun(args);
                                /*
                                 * Fire up an event handler, if the connection was just
                                 * opened. Since this was done from the run command
                                 * we don't stop the VM on its VM start event (so
                                 * arg 2 is false).
                                 */
                                if ((handler == null) && Env.connection().isOpen()) {
                                    handler = new EventHandler(this, false);
                                }
                            } else if (name.equals("memory")) {
                                evaluator.commandMemory();
                            } else if (name.equals("gc")) {
                                evaluator.commandGC();
                            } else if (name.equals("stop")) {
                                evaluator.commandStop(args);
                            } else if (name.equals("clear")) {
                                evaluator.commandClear(args);
                            } else if (name.equals("watch")) {
                                evaluator.commandWatch(args);
                            } else if (name.equals("unwatch")) {
                                evaluator.commandUnwatch(args);
                            } else if (name.equals("list")) {
                                nextListTarget = evaluator.commandList(args, repeat ? nextListTarget : null);
                            } else if (name.equals("lines")) { // Undocumented command: useful for testing.
                                evaluator.commandLines(args);
                            } else if (name.equals("classpath")) {
                                evaluator.commandClasspath(args);
                            } else if (name.equals("use") || name.equals("sourcepath")) {
                                evaluator.commandUse(args);
                            } else if (name.equals("monitor")) {
                                monitorCommand(args);
                            } else if (name.equals("unmonitor")) {
                                unmonitorCommand(args);
                            } else if (name.equals("lock")) {
                                evaluator.commandLock(args);
                                showPrompt = false;        // asynchronous command
                            } else if (name.equals("threadlocks")) {
                                evaluator.commandThreadlocks(args);
                            } else if (name.equals("disablegc")) {
                                evaluator.commandDisableGC(args);
                                showPrompt = false;        // asynchronous command
                            } else if (name.equals("enablegc")) {
                                evaluator.commandEnableGC(args);
                                showPrompt = false;        // asynchronous command
                            } else if (name.equals("save")) { // Undocumented command: useful for testing.
                                evaluator.commandSave(args);
                                showPrompt = false;        // asynchronous command
                            } else if (name.equals("bytecodes")) { // Undocumented command: useful for testing.
                                evaluator.commandBytecodes(args);
                            } else if (name.equals("redefine")) {
                                evaluator.commandRedefine(args);
                            } else if (name.equals("pop")) {
                                evaluator.commandPopFrames(args, false);
                            } else if (name.equals("reenter")) {
                                evaluator.commandPopFrames(args, true);
                            } else if (name.equals("extension")) {
                                evaluator.commandExtension(args);
                            } else if (name.equals("exclude")) {
                                evaluator.commandExclude(args);
                            } else if (name.equals("read")) {
                                readCommand(args);
                            } else if (name.equals("dbgtrace")) {
                                evaluator.commandDbgTrace(args);
                            } else if (name.equals("help") || name.equals("?")) {
                                help();
                            } else if (name.equals("version")) {
                                evaluator.commandVersion(progname,
                                                         Bootstrap.virtualMachineManager());
                            } else if (name.equals("repeat")) {
                                doRepeat(args);
                            } else if (name.equals("quit") || name.equals("exit")) {
                                if (handler != null) {
                                    handler.shutdown();
                                }
                                Env.shutdown();
                            } else {
                                MessageOutput.println("Unrecognized command.  Try help...", name);
                            }
                        } catch (VMCannotBeModifiedException rovm) {
                            MessageOutput.println("Command is not supported on a read-only VM connection", name);
                        } catch (UnsupportedOperationException uoe) {
                            MessageOutput.println("Command is not supported on the target VM", name);
                        } catch (VMNotConnectedException vmnse) {
                            MessageOutput.println("Command not valid until the VM is started with the run command",
                                                  name);
                        } catch (Exception e) {
                            MessageOutput.printException("Internal exception:", e);
                        }
                    }
                }
            }
        }
        if (showPrompt) {
            MessageOutput.printPrompt();
        }

        if (LIST_RESET.contains(name)) {
            nextListTarget = null;
        }

        return name;
    }

    /*
     * Maintain a list of commands to execute each time the VM is suspended.
     */
    void monitorCommand(StringTokenizer t) {
        if (t.hasMoreTokens()) {
            ++monitorCount;
            monitorCommands.add(monitorCount + ": " + t.nextToken(""));
        } else {
            for (String cmd : monitorCommands) {
                MessageOutput.printDirectln(cmd);// Special case: use printDirectln()
            }
        }
    }

    void unmonitorCommand(StringTokenizer t) {
        if (t.hasMoreTokens()) {
            String monTok = t.nextToken();
            int monNum;
            try {
                monNum = Integer.parseInt(monTok);
            } catch (NumberFormatException exc) {
                MessageOutput.println("Not a monitor number:", monTok);
                return;
            }
            String monStr = monTok + ":";
            for (String cmd : monitorCommands) {
                StringTokenizer ct = new StringTokenizer(cmd);
                if (ct.nextToken().equals(monStr)) {
                    monitorCommands.remove(cmd);
                    MessageOutput.println("Unmonitoring", cmd);
                    return;
                }
            }
            MessageOutput.println("No monitor numbered:", monTok);
        } else {
            MessageOutput.println("Usage: unmonitor <monitor#>");
        }
    }

    void readCommand(StringTokenizer t) {
        if (t.hasMoreTokens()) {
            String cmdfname = t.nextToken();
            if (!readCommandFile(new File(cmdfname))) {
                MessageOutput.println("Could not open:", cmdfname);
            }
        } else {
            MessageOutput.println("Usage: read <command-filename>");
        }
    }

    protected void doRepeat(StringTokenizer t) {
        if (t.hasMoreTokens()) {
            var choice = t.nextToken().toLowerCase();
            if ((choice.equals("on") || choice.equals("off")) && !t.hasMoreTokens()) {
                repeat = choice.equals("on");
            } else {
                MessageOutput.println("repeat usage");
            }
        } else {
            MessageOutput.println(repeat ? "repeat is on" : "repeat is off");
        }
    }

    /**
     * Read and execute a command file.  Return true if the file was read
     * else false;
     */
    boolean readCommandFile(File f) {
        BufferedReader inFile = null;
        try {
            if (f.canRead()) {
                // Process initial commands.
                MessageOutput.println("*** Reading commands from", f.getPath());
                inFile = new BufferedReader(new FileReader(f));
                String ln;
                while ((ln = inFile.readLine()) != null) {
                    StringTokenizer t = new StringTokenizer(ln);
                    if (t.hasMoreTokens()) {
                        executeCommand(t);
                    }
                }
            }
        } catch (IOException e) {
        } finally {
            if (inFile != null) {
                try {
                    inFile.close();
                } catch (Exception exc) {
                }
            }
        }
        return inFile != null;
    }

    /**
     * Try to read commands from dir/fname, unless
     * the canonical path passed in is the same as that
     * for dir/fname.
     * Return null if that file doesn't exist,
     * else return the canonical path of that file.
     */
    String readStartupCommandFile(String dir, String fname, String canonPath) {
        File dotInitFile = new File(dir, fname);
        if (!dotInitFile.exists()) {
            return null;
        }

        String myCanonFile;
        try {
            myCanonFile = dotInitFile.getCanonicalPath();
        } catch (IOException ee) {
            MessageOutput.println("Could not open:", dotInitFile.getPath());
            return null;
        }
        if (canonPath == null || !canonPath.equals(myCanonFile)) {
            if (!readCommandFile(dotInitFile)) {
                MessageOutput.println("Could not open:", dotInitFile.getPath());
            }
        }
        return myCanonFile;
    }


    public TTY() throws Exception {

        MessageOutput.println("Initializing progname", progname);

        if (Env.connection().isOpen() && Env.vm().canBeModified()) {
            /*
             * Connection opened on startup. Start event handler
             * immediately, telling it (through arg 2) to stop on the
             * VM start event.
             */
            this.handler = new EventHandler(this, true);
        }
        try {
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(System.in));

            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);

            /*
             * Read start up files.  This mimics the behavior
             * of gdb which will read both ~/.gdbinit and then
             * ./.gdbinit if they exist.  We have the twist that
             * we allow two different names, so we do this:
             *  if ~/jdb.ini exists,
             *      read it
             *  else if ~/.jdbrc exists,
             *      read it
             *
             *  if ./jdb.ini exists,
             *      if it hasn't been read, read it
             *      It could have been read above because ~ == .
             *      or because of symlinks, ...
             *  else if ./jdbrx exists
             *      if it hasn't been read, read it
             */
            {
                String userHome = System.getProperty("user.home");
                String canonPath;

                if ((canonPath = readStartupCommandFile(userHome, "jdb.ini", null)) == null) {
                    // Doesn't exist, try alternate spelling
                    canonPath = readStartupCommandFile(userHome, ".jdbrc", null);
                }

                String userDir = System.getProperty("user.dir");
                if (readStartupCommandFile(userDir, "jdb.ini", canonPath) == null) {
                    // Doesn't exist, try alternate spelling
                    readStartupCommandFile(userDir, ".jdbrc", canonPath);
                }
            }

            // Process interactive commands.
            MessageOutput.printPrompt();

            String lastLine = null;
            String lastCommandName = null;
            while (true) {
                String ln = in.readLine();
                if (ln == null) {
                    /*
                     *  Jdb is being shutdown because debuggee exited, ignore any 'null'
                     *  returned by readLine() during shutdown. JDK-8154144.
                     */
                    if (!isShuttingDown()) {
                        MessageOutput.println("Input stream closed.");
                    }
                    ln = "quit";
                }

                if (ln.startsWith("!!") && lastLine != null) {
                    ln = lastLine + ln.substring(2);
                    MessageOutput.printDirectln(ln);// Special case: use printDirectln()
                }

                StringTokenizer t = new StringTokenizer(ln);
                if (t.hasMoreTokens()) {
                    lastLine = ln;
                    lastCommandName = executeCommand(t);
                } else if (repeat && lastLine != null && REPEATABLE.contains(lastCommandName)) {
                    executeCommand(new StringTokenizer(lastLine));
                } else {
                    MessageOutput.printPrompt();
                }
            }
        } catch (VMDisconnectedException e) {
            handler.handleDisconnectedException();
        }
    }

    private static void usage() {
        MessageOutput.println("zz usage text", new Object [] {progname,
                                                     File.pathSeparator});
        System.exit(0);
    }

    static void usageError(String messageKey) {
        MessageOutput.println(messageKey);
        MessageOutput.println();
        usage();
    }

    static void usageError(String messageKey, String argument) {
        MessageOutput.println(messageKey, argument);
        MessageOutput.println();
        usage();
    }

    private static boolean supportsSharedMemory() {
        for (Connector connector :
                 Bootstrap.virtualMachineManager().allConnectors()) {
            if (connector.transport() == null) {
                continue;
            }
            if ("dt_shmem".equals(connector.transport().name())) {
                return true;
            }
        }
        return false;
    }

    private static String addressToSocketArgs(String address) {
        int index = address.indexOf(':');
        if (index != -1) {
            String hostString = address.substring(0, index);
            String portString = address.substring(index + 1);
            return "hostname=" + hostString + ",port=" + portString;
        } else {
            return "port=" + address;
        }
    }

    private static boolean hasWhitespace(String string) {
        int length = string.length();
        for (int i = 0; i < length; i++) {
            if (Character.isWhitespace(string.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String addArgument(String string, String argument) {
        if (hasWhitespace(argument) || argument.indexOf(',') != -1) {
            // Quotes were stripped out for this argument, add 'em back.
            StringBuilder sb = new StringBuilder(string);
            sb.append('"');
            for (int i = 0; i < argument.length(); i++) {
                char c = argument.charAt(i);
                if (c == '"') {
                    sb.append('\\');
                }
                sb.append(c);
            }
            sb.append("\" ");
            return sb.toString();
        } else {
            return string + argument + ' ';
        }
    }

    public static void main(String argv[]) throws MissingResourceException {
        String cmdLine = "";
        String javaArgs = "";
        int traceFlags = VirtualMachine.TRACE_NONE;
        boolean launchImmediately = false;
        String connectSpec = null;

        MessageOutput.textResources = ResourceBundle.getBundle
            ("com.sun.tools.example.debug.tty.TTYResources",
             Locale.getDefault());

        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (token.equals("-dbgtrace")) {
                if ((i == argv.length - 1) ||
                    ! Character.isDigit(argv[i+1].charAt(0))) {
                    traceFlags = VirtualMachine.TRACE_ALL;
                } else {
                    String flagStr = "";
                    try {
                        flagStr = argv[++i];
                        traceFlags = Integer.decode(flagStr).intValue();
                    } catch (NumberFormatException nfe) {
                        usageError("dbgtrace flag value must be an integer:",
                                   flagStr);
                        return;
                    }
                }
            } else if (token.equals("-X")) {
                usageError("Use java minus X to see");
                return;
            } else if (
                   // Standard VM options passed on
                   token.equals("-v") || token.startsWith("-v:") ||  // -v[:...]
                   token.startsWith("-verbose") ||                  // -verbose[:...]
                   token.startsWith("-D") ||
                   // -classpath handled below
                   // NonStandard options passed on
                   token.startsWith("-X") ||
                   // Old-style options (These should remain in place as long as
                   //  the standard VM accepts them)
                   token.equals("-noasyncgc") || token.equals("-prof") ||
                   token.equals("-verify") ||
                   token.equals("-verifyremote") ||
                   token.equals("-verbosegc") ||
                   token.startsWith("-ms") || token.startsWith("-mx") ||
                   token.startsWith("-ss") || token.startsWith("-oss") ) {

                javaArgs = addArgument(javaArgs, token);
            } else if (token.equals("-tclassic")) {
                usageError("Classic VM no longer supported.");
                return;
            } else if (token.equals("-tclient")) {
                // -client must be the first one
                javaArgs = "-client " + javaArgs;
            } else if (token.equals("-tserver")) {
                // -server must be the first one
                javaArgs = "-server " + javaArgs;
            } else if (token.equals("-sourcepath")) {
                if (i == (argv.length - 1)) {
                    usageError("No sourcepath specified.");
                    return;
                }
                Env.setSourcePath(argv[++i]);
            } else if (token.equals("-classpath")) {
                if (i == (argv.length - 1)) {
                    usageError("No classpath specified.");
                    return;
                }
                javaArgs = addArgument(javaArgs, token);
                javaArgs = addArgument(javaArgs, argv[++i]);
            } else if (token.equals("-attach")) {
                if (connectSpec != null) {
                    usageError("cannot redefine existing connection", token);
                    return;
                }
                if (i == (argv.length - 1)) {
                    usageError("No attach address specified.");
                    return;
                }
                String address = argv[++i];

                /*
                 * -attach is shorthand for one of the reference implementation's
                 * attaching connectors. Use the shared memory attach if it's
                 * available; otherwise, use sockets. Build a connect
                 * specification string based on this decision.
                 */
                if (supportsSharedMemory()) {
                    connectSpec = "com.sun.jdi.SharedMemoryAttach:name=" +
                                   address;
                } else {
                    String suboptions = addressToSocketArgs(address);
                    connectSpec = "com.sun.jdi.SocketAttach:" + suboptions;
                }
            } else if (token.equals("-listen") || token.equals("-listenany")) {
                if (connectSpec != null) {
                    usageError("cannot redefine existing connection", token);
                    return;
                }
                String address = null;
                if (token.equals("-listen")) {
                    if (i == (argv.length - 1)) {
                        usageError("No attach address specified.");
                        return;
                    }
                    address = argv[++i];
                }

                /*
                 * -listen[any] is shorthand for one of the reference implementation's
                 * listening connectors. Use the shared memory listen if it's
                 * available; otherwise, use sockets. Build a connect
                 * specification string based on this decision.
                 */
                if (supportsSharedMemory()) {
                    connectSpec = "com.sun.jdi.SharedMemoryListen:";
                    if (address != null) {
                        connectSpec += ("name=" + address);
                    }
                } else {
                    connectSpec = "com.sun.jdi.SocketListen:";
                    if (address != null) {
                        connectSpec += addressToSocketArgs(address);
                    }
                }
            } else if (token.equals("-launch")) {
                launchImmediately = true;
            } else if (token.equals("-listconnectors")) {
                Commands evaluator = new Commands();
                evaluator.commandConnectors(Bootstrap.virtualMachineManager());
                return;
            } else if (token.equals("-connect")) {
                /*
                 * -connect allows the user to pick the connector
                 * used in bringing up the target VM. This allows
                 * use of connectors other than those in the reference
                 * implementation.
                 */
                if (connectSpec != null) {
                    usageError("cannot redefine existing connection", token);
                    return;
                }
                if (i == (argv.length - 1)) {
                    usageError("No connect specification.");
                    return;
                }
                connectSpec = argv[++i];
            } else if (token.equals("-?") ||
                       token.equals("-h") ||
                       token.equals("--help") ||
                       // -help: legacy.
                       token.equals("-help")) {
                usage();
            } else if (token.equals("-version")) {
                Commands evaluator = new Commands();
                evaluator.commandVersion(progname,
                                         Bootstrap.virtualMachineManager());
                System.exit(0);
            } else if (token.startsWith("-")) {
                usageError("invalid option", token);
                return;
            } else {
                // Everything from here is part of the command line
                cmdLine = addArgument("", token);
                for (i++; i < argv.length; i++) {
                    cmdLine = addArgument(cmdLine, argv[i]);
                }
                break;
            }
        }

        /*
         * Unless otherwise specified, set the default connect spec.
         */

        /*
         * Here are examples of jdb command lines and how the options
         * are interpreted as arguments to the program being debugged.
         *                     arg1       arg2
         *                     ----       ----
         * jdb hello a b       a          b
         * jdb hello "a b"     a b
         * jdb hello a,b       a,b
         * jdb hello a, b      a,         b
         * jdb hello "a, b"    a, b
         * jdb -connect "com.sun.jdi.CommandLineLaunch:main=hello  a,b"   illegal
         * jdb -connect  com.sun.jdi.CommandLineLaunch:main=hello "a,b"   illegal
         * jdb -connect 'com.sun.jdi.CommandLineLaunch:main=hello "a,b"'  arg1 = a,b
         * jdb -connect 'com.sun.jdi.CommandLineLaunch:main=hello "a b"'  arg1 = a b
         * jdb -connect 'com.sun.jdi.CommandLineLaunch:main=hello  a b'   arg1 = a  arg2 = b
         * jdb -connect 'com.sun.jdi.CommandLineLaunch:main=hello "a," b' arg1 = a, arg2 = b
         */
        if (connectSpec == null) {
            connectSpec = "com.sun.jdi.CommandLineLaunch:";
        } else if (!connectSpec.endsWith(",") && !connectSpec.endsWith(":")) {
            connectSpec += ","; // (Bug ID 4285874)
        }

        cmdLine = cmdLine.trim();
        javaArgs = javaArgs.trim();

        if (cmdLine.length() > 0) {
            if (!connectSpec.startsWith("com.sun.jdi.CommandLineLaunch:")) {
                usageError("Cannot specify command line with connector:",
                           connectSpec);
                return;
            }
            connectSpec += "main=" + cmdLine + ",";
        }

        if (javaArgs.length() > 0) {
            if (!connectSpec.startsWith("com.sun.jdi.CommandLineLaunch:")) {
                usageError("Cannot specify target vm arguments with connector:",
                           connectSpec);
                return;
            }
        }

        try {
            Env.init(connectSpec, launchImmediately, traceFlags, javaArgs);
            new TTY();
        } catch(Exception e) {
            MessageOutput.printException("Internal exception:", e);
        }
    }
}
