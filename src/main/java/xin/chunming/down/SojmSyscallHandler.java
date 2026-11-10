package xin.chunming.down;

import com.github.unidbg.Emulator;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.linux.ARM32SyscallHandler;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.unix.struct.TimeSpec;
import com.sun.jna.Pointer;
import unicorn.ArmConst;

import java.util.HashMap;
import java.util.Map;

public class SojmSyscallHandler extends ARM32SyscallHandler {
    private final Map<Integer, byte[]> sigActs = new HashMap<>();

    public SojmSyscallHandler(SvcMemory svcMemory) { super(svcMemory); }

    // unidbg 的等待器不接受 <1ms 的超时，Go 的 usleep(20) / futex 短超时会触发，向上取整到 1ms
    private void atLeast1ms(Emulator<?> emulator, Pointer p) {
        if (p == null) return;
        TimeSpec ts = TimeSpec.createTimeSpec(emulator, p);
        if (ts.toMillis() <= 0) { ts.setMillis(1); ts.pack(); }
    }

    @Override
    protected int nanosleep(Emulator<?> emulator) {
        atLeast1ms(emulator, emulator.getContext().getPointerArg(0));
        return super.nanosleep(emulator);
    }

    @Override
    protected int futex(Emulator<?> emulator) {
        RegisterContext ctx = emulator.getContext();
        int op = ctx.getIntArg(1) & ~(0x80 | 0x100);      // 去掉 PRIVATE / CLOCK_REALTIME 标志
        if (op == 0) atLeast1ms(emulator, ctx.getPointerArg(3)); // FUTEX_WAIT 的相对超时
        return super.futex(emulator);
    }

    @Override
    protected boolean handleUnknownSyscall(Emulator<?> emulator, int NR) {
        if (NR == 174) { // rt_sigaction
            RegisterContext ctx = emulator.getContext();
            int sig = ctx.getIntArg(0);
            Pointer act = ctx.getPointerArg(1), oact = ctx.getPointerArg(2);
            int ret = 0;
            if (sig < 1 || sig > 64 || sig == 9 || sig == 19) {
                ret = -22;
            } else {
                byte[] old = sigActs.get(sig);
                if (oact != null) oact.write(0, old != null ? old : new byte[20], 0, 20);
                if (act != null) sigActs.put(sig, act.getByteArray(0, 20));
            }
            emulator.getBackend().reg_write(ArmConst.UC_ARM_REG_R0, ret);
            return true;
        }
        System.out.println("[unknown syscall] NR=" + NR);   // 方便发现下一个缺失的调用
        return super.handleUnknownSyscall(emulator, NR);
    }
}