package com.deepseek.pet;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 手写 Binder 接口（工程没有 AIDL 编译器，所以不能用 .aidl 生成）。
 *
 * <p>Shizuku 的 UserService 机制要求提供一个 Binder 接口给双方通信。
 * 官方示例用 AIDL 生成 {@code Stub}/{@code Proxy}；本工程是手工 javac/aapt2 构建、
 * 没有 aidl 工具，所以这里手写等价的 Stub/Proxy。
 *
 * <p>★ 手写的关键是四件事必须与 AIDL 生成物一致：
 * <ol>
 *   <li><b>接口继承 {@code IInterface}（不是 {@code IBinder}）</b> ——
 *       我第一版写成 {@code extends IBinder}，编译报
 *       "Stub 无法转换为 IInterface" 且 Proxy 要实现一堆 IBinder 抽象方法。
 *       AIDL 生成物继承的是 {@code IInterface}，只含一个 {@code asBinder()}。</li>
 *   <li>{@code DESCRIPTOR} 字符串 —— 自定义，两端一致即可</li>
 *   <li>事务码（这里 {@code 1}/{@code 2}）—— 两端一致即可</li>
 *   <li>{@code onTransact} 里先 {@code enforceInterface}、再 {@code writeNoException}</li>
 * </ol>
 * 少了 {@code writeNoException}，调用端会在读结果时抛异常。
 */
public interface IFpsUserService extends IInterface {

    /** 以 shell 身份执行一条命令，返回 stdout（失败返回 null）。 */
    String exec(String cmd) throws RemoteException;

    /** 执行并返回 stdout+stderr 合并结果（调试用）。 */
    String execWithErr(String cmd) throws RemoteException;

    abstract class Stub extends Binder implements IFpsUserService {

        private static final String DESCRIPTOR = "com.deepseek.pet.IFpsUserService";
        private static final int TRANSACTION_exec = 1;
        private static final int TRANSACTION_execWithErr = 2;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        /** 供调用端把 IBinder 转成接口。 */
        public static IFpsUserService asInterface(IBinder b) {
            if (b == null) return null;
            if (b instanceof IFpsUserService) return (IFpsUserService) b;
            return new Proxy(b);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            switch (code) {
                case TRANSACTION_exec: {
                    data.enforceInterface(DESCRIPTOR);
                    String arg = data.readString();
                    String result = exec(arg);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_execWithErr: {
                    data.enforceInterface(DESCRIPTOR);
                    String arg = data.readString();
                    String result = execWithErr(arg);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    /** 调用端代理：把方法调用打包成 transact。 */
    class Proxy implements IFpsUserService {

        private static final String DESCRIPTOR = "com.deepseek.pet.IFpsUserService";
        private final IBinder remote;

        public Proxy(IBinder remote) {
            this.remote = remote;
        }

        @Override
        public IBinder asBinder() {
            return remote;
        }

        @Override
        public String exec(String cmd) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(cmd);
                remote.transact(1, data, reply, 0);
                reply.readException();
                return reply.readString();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        @Override
        public String execWithErr(String cmd) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(cmd);
                remote.transact(2, data, reply, 0);
                reply.readException();
                return reply.readString();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }
}
