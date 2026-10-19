package dev.atmpilot.tools;

import com.sun.tools.attach.VirtualMachine;

public final class AttachPatch {
    public static void main(String[] arguments)throws Exception {
        if(arguments.length!=3)throw new IllegalArgumentException("Usage: AttachPatch PID agent.jar request.properties");
        VirtualMachine vm=VirtualMachine.attach(arguments[0]);
        try{vm.loadAgent(arguments[1],arguments[2]);}finally{vm.detach();}
    }
}
