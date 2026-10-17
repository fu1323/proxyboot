package xin.chunming.down;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Module;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.android.AndroidARMEmulator;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.AbstractJni;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.memory.SvcMemory;
import com.github.unidbg.linux.android.dvm.BaseVM;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.StringObject;
import com.github.unidbg.linux.android.dvm.VaList;

import java.io.*;

import com.github.unidbg.unix.UnixSyscallHandler;

import java.io.File;

public class unidbgtool extends AbstractJni {
    private static final String APK_PATH =
            "/data/app/dl.wnlhd.xpniey/base.apk";
    private final AndroidEmulator emulator;
    private final VM vm;
    private final Module module;

    public unidbgtool() {

        // 1. 首先创建 ARM32 Android 模拟器
        emulator = new AndroidEmulatorBuilder(false) {
            @Override public AndroidEmulator build() {
                return new AndroidARMEmulator(processName, rootDir, backendFactories) {
                    @Override
                    protected UnixSyscallHandler<AndroidFileIO> createSyscallHandler(SvcMemory svcMemory) {
                        return new SojmSyscallHandler(svcMemory);
                    }
                };
            }
        }.setProcessName("dl.wnlhd.xpniey").build();

        final File apk = new File("libs/target.apk");
        final String vpath = "/data/app/dl.wnlhd.xpniey/lib/arm";
        emulator.getSyscallHandler().addIOResolver(
                (IOResolver<AndroidFileIO>) (emu, pathname, oflags) -> {
                    if (APK_PATH.equals(pathname)) {
                        return FileResult.success(
                                new SimpleFileIO(
                                        oflags,
                                        new File("libs/target.apk"),
                                        pathname
                                )
                        );
                    }
                    return null;
                }
        );
        emulator.getSyscallHandler().setEnableThreadDispatcher(true);   // ← 新增
        emulator.getMemory().setLibraryResolver(new AndroidResolver(23));

        // 2. 配置 Android 系统库解析
        emulator.getMemory().setLibraryResolver(
                new AndroidResolver(23)
        );

        // 3. 检查 APK 是否存在
        File apkFile = new File("libs/target.apk");

        if (!apkFile.isFile()) {
            throw new IllegalStateException(
                    "找不到目标 APK：" + apkFile.getAbsolutePath()
            );
        }

        // 4. 创建关联 APK 的 Dalvik VM
        vm = emulator.createDalvikVM(apkFile);

        // 5. 打开详细日志
        vm.setVerbose(true);


        // 6. 注册自定义 JNI 处理器
        vm.setJni(this);

        // 7. 检查 SO 是否存在
        File soFile = new File("libs/libsojm.so");

        if (!soFile.isFile()) {
            throw new IllegalStateException(
                    "找不到 SO 文件：" + soFile.getAbsolutePath()
            );
        }

        // 8. 加载 SO
        DalvikModule dm = vm.loadLibrary(soFile, false);
        module = dm.getModule();
//        emulator.traceCode();
        System.out.println("SO 加载成功！");
        System.out.println(
                "Module base = 0x" + Long.toHexString(module.base)
        );
    }

    //    public static void main(String[] args) {
//        SojmTest test = new SojmTest();
//
//        try {
//            System.out.println("unidbg 初始化完成。");
//        } finally {
//            try {
//                test.emulator.close();
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
//        }
//    }
    @Override
    public DvmObject<?> callStaticObjectMethodV(
            BaseVM vm, DvmClass c, String sig, VaList a) {

        if (sig.equals(
                "android/app/ActivityThread->currentActivityThread()Landroid/app/ActivityThread;")) {
            return vm.resolveClass("android/app/ActivityThread")
                    .newObject(null);
        }

        return super.callStaticObjectMethodV(vm, c, sig, a);
    }

    @Override
    public DvmObject<?> callObjectMethodV(
            BaseVM vm, DvmObject<?> o, String sig, VaList a) {

        switch (sig) {
            case "android/app/ActivityThread->getApplication()Landroid/app/Application;":
                return vm.resolveClass("android/app/Application")
                        .newObject(null);

            case "android/app/Application->getPackageName()Ljava/lang/String;":
                return new StringObject(vm, "dl.wnlhd.xpniex");

            case "android/app/Application->getPackageCodePath()Ljava/lang/String;":
                return new StringObject(vm, APK_PATH);
        }

        return super.callObjectMethodV(vm, o, sig, a);
    }
    public static String doing(StringBuilder str1) throws IOException {
        unidbgtool test = new unidbgtool();
        /*艺校大长腿美术生花样真多，浪漫会玩操到嗷嗷叫直呼爽歪*/
        try {
//            BufferedReader bufferedReader = new BufferedReader(new FileReader("encrypt.txt"));
//            String line;
//            StringBuilder stringBuilder = new StringBuilder();
//            while ((line=bufferedReader.readLine())!=null){
//                stringBuilder.append(line);
//            }




            String arg1 = str1.toString();
            String arg2 = "NDQUNFAMAwZVDVAEAAdXAlABBFdWVQwNA1EDDFcEB1UMUAdSKiosHAAnDAQpAg==";
            System.out.println("开始调用 encrypt...");
            System.out.println("encrypt result = " +
                    test.callEncrypt(arg1, arg2));

            System.out.println("开始调用 decrypt...");
            String s = test.callDecrypt(arg1, arg2);
            System.out.println("decrypt result = " +
                    s);
            return  s;

        } finally {
            test.emulator.close();
           // return  null;
        }

    }

    private String callEncrypt(String arg1, String arg2) {
        DvmClass encryptUtil = vm.resolveClass("com/qq/lib/EncryptUtil");

        StringObject result = encryptUtil.callStaticJniMethodObject(
                emulator,
                "encrypt(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new StringObject(vm, arg1),
                new StringObject(vm, arg2)
        );

        return result == null ? null : result.getValue();
    }

    private String callDecrypt(String arg1, String arg2) {
        DvmClass encryptUtil = vm.resolveClass("com/qq/lib/EncryptUtil");

        StringObject result = encryptUtil.callStaticJniMethodObject(
                emulator,
                "decrypt(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                new StringObject(vm, arg1),
                new StringObject(vm, arg2)
        );

        return result == null ? null : result.getValue();
    }
}