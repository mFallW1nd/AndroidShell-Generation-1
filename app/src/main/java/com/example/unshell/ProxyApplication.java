package com.example.unshell;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dalvik.system.DexClassLoader;

public class ProxyApplication extends Application {

    private static final String TAG="ProxyApplication";
    private String dexPath;
    private String libPath;
    private String apkFileName;

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Log.i(TAG,"进入attachBaseContext方法");
        try {
            File dex=getDir("payload_dex",MODE_PRIVATE);
            File lib=getDir("payload_lib",MODE_PRIVATE);
            dexPath=dex.getAbsolutePath();
            libPath=lib.getAbsolutePath();

            apkFileName = dex.getAbsolutePath()+File.separator+"shell.apk";
            File dexFile = new File(apkFileName);
            dexFile.createNewFile();

            byte[] dexData = readDexFileFromApk();
            splitPayloadFromDex(dexData);

            Object activityThreadObject = RefinvokeMethod.invokeStaticMethod(
                    "android.app.ActivityThread",
                    "currentActivityThread",
                    new Class[]{},
                    new Object[]{}
            );

            String packageName = getPackageName();
            final ArrayMap<?, ?> packages = (ArrayMap<?, ?>) RefinvokeMethod.getField(
                    "android.app.ActivityThread",
                    activityThreadObject,
                    "mPackages"
            );

            assert packages != null;
            WeakReference<?> weakReference = (WeakReference<?>) packages.get(packageName);
            ClassLoader classLoader = (ClassLoader) RefinvokeMethod.getField(
                    "android.app.LoadedApk",
                    weakReference.get(),
                    "mClassLoader"
            );

            DexClassLoader dexClassLoader = new DexClassLoader(
                    apkFileName,
                    dexPath,
                    libPath,
                    classLoader
            );

            RefinvokeMethod.setField(
                    "android.app.LoadedApk",
                    "mClassLoader",
                    weakReference.get(),
                    dexClassLoader
            );

            try {
                dexClassLoader.loadClass("com.example.sourceapk.MainActivity");
                Log.i(TAG,"MainActivity类加载完毕");
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    public void onCreate() {
        super.onCreate();

        loadResources(apkFileName);
        Log.i(TAG,"进入onCreate方法");
        String applicationName="";
        ApplicationInfo ai;

        try {
            ai=getPackageManager().getApplicationInfo(
                    getPackageName(),
                    PackageManager.GET_META_DATA
            );
            applicationName=ai.metaData.getString("ApplicationName");
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        Object activityThreadObj=RefinvokeMethod.invokeStaticMethod(
                "android.app.ActivityThread",
                "currentActivityThread",
                new Class[]{},
                new Object[]{}
        );
        Object mBoundApplication=RefinvokeMethod.getField(
                "android.app.ActivityThread",
                activityThreadObj,
                "mBoundApplication"
        );
        Object info=RefinvokeMethod.getField(
                "android.app.ActivityThread$AppBindData",
                mBoundApplication,
                "info"
        );
        // 将当前进程的mApplication设置为null
        RefinvokeMethod.setField(
                "android.app.LoadedApk",
                "mApplication",
                info,
                null
        );

        Object mInitApplication=RefinvokeMethod.getField(
                "android.app.ActivityThread",
                activityThreadObj,
                "mInitialApplication"
        );
        ArrayList<?> mAllApplications= (ArrayList<?>) RefinvokeMethod.getField(
                "android.app.ActivityThread",
                activityThreadObj,
                "mAllApplications"
        );

        assert mAllApplications != null;
        mAllApplications.remove(mInitApplication);
        ApplicationInfo mApplicationInfo= (ApplicationInfo) RefinvokeMethod.getField(
                "android.app.LoadedApk",
                info,
                "mApplicationInfo"
        );
        ApplicationInfo appInfo= (ApplicationInfo) RefinvokeMethod.getField(
                "android.app.ActivityThread$AppBindData",
                mBoundApplication,
                "appInfo"
        );

        assert mApplicationInfo != null;
        assert appInfo != null;
        mApplicationInfo.className = applicationName;
        appInfo.className = applicationName;

        // 执行makeApplication(false,null)
        Application app= (Application) RefinvokeMethod.invokeMethod(
                "android.app.LoadedApk",
                "makeApplication",
                info,
                new Class[]{boolean.class, Instrumentation.class},
                new Object[]{false,null}
        );

        RefinvokeMethod.setField(
                "android.app.ActivityThread",
                "mInitialApplication",
                activityThreadObj,
                app
        );

        ArrayMap<?, ?> mProviderMap= (ArrayMap<?, ?>) RefinvokeMethod.getField(
                "android.app.ActivityThread",
                activityThreadObj,
                "mProviderMap"
        );

        assert mProviderMap != null;
        for (Object mProviderClientRecord : mProviderMap.values()) {
            Object mLocalProvider = RefinvokeMethod.getField(
                    "android.app.ActivityThread$ProviderClientRecord",
                    mProviderClientRecord,
                    "mLocalProvider"
            );
            RefinvokeMethod.setField(
                    "android.content.ContentProvider",
                    "mContext",
                    mLocalProvider,
                    app
            );
        }
        if (app != null)
            app.onCreate();
    }

    private void splitPayloadFromDex(byte[] dexData) throws IOException {
        int len=dexData.length;
        byte[] apkLen = new byte[4];
        System.arraycopy(dexData,len-4,apkLen,0,4);

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(apkLen);
        DataInputStream dataInputStream = new DataInputStream(byteArrayInputStream);
        int readInt = dataInputStream.readInt();
        byte[] apk = new byte[readInt];
        System.arraycopy(dexData,len-4-readInt,apk,0,readInt);

        decrypt(apk);

        File file = new File(apkFileName);
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        fileOutputStream.write(apk);
        fileOutputStream.close();

        ZipInputStream zipInputStream =
                new ZipInputStream(
                        new BufferedInputStream(
                                new FileInputStream(file)));

        while (true){
            ZipEntry zipEntry = zipInputStream.getNextEntry();
            if (zipEntry == null){
                zipInputStream.close();
                break;
            }

            String name = zipEntry.getName();
            if (name.startsWith("lib/") && name.endsWith(".so")){
                File storeFile = new File(libPath+"/"+ name.substring(name.lastIndexOf("/")));

                storeFile.createNewFile();
                FileOutputStream fos = new FileOutputStream(storeFile);
                byte[] byteArray = new byte[1024];
                while (true){
                    int i = zipInputStream.read(byteArray);
                    if (i==-1){
                        break;
                    }
                    fos.write(byteArray,0,i);
                }
                fos.flush();
                fos.close();
            }
            zipInputStream.closeEntry();
        }
        zipInputStream.close();
    }

    private void decrypt(byte[] apk) {
        for (int i = 0; i < apk.length; i++){
            apk[i] ^= 0xFF;
        }
    }

    private byte[] readDexFileFromApk() throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ZipInputStream zipInputStream =
            new ZipInputStream(
                new BufferedInputStream(
                        new FileInputStream(getApplicationInfo().sourceDir)));

        while (true){
            ZipEntry zipEntry = zipInputStream.getNextEntry();
            if (zipEntry == null){
                zipInputStream.close();
                break;
            }

            if (zipEntry.getName().equals("classes.dex")){
                byte[] bytes=new byte[1024];
                while (true){
                    int numOfBytes = zipInputStream.read(bytes);
                    if (numOfBytes == -1){
                        break;
                    }
                    byteArrayOutputStream.write(bytes,0,numOfBytes);
                }
            }
            zipInputStream.closeEntry();
        }
        zipInputStream.close();
        return byteArrayOutputStream.toByteArray();
    }

    protected AssetManager assetManager;
    protected Resources resources;
    protected Resources.Theme theme;

    protected void loadResources(String dexPath){
        try {
            AssetManager manager = AssetManager.class.newInstance();
            Method method = manager.getClass().getMethod("addAssetPath",String.class);
            method.invoke(manager,dexPath);
            assetManager = manager;
        } catch (Exception e) {
            e.printStackTrace();
        }
        Resources superRes = super.getResources();
        superRes.getDisplayMetrics();
        superRes.getConfiguration();
        resources = new Resources(assetManager, superRes.getDisplayMetrics(),superRes.getConfiguration());
        theme = resources.newTheme();
        theme.setTo(super.getTheme());
    }

    @Override
    public AssetManager getAssets() {
        return assetManager == null?super.getAssets():assetManager;
    }

    @Override
    public Resources getResources() {
        return resources == null?super.getResources():resources;
    }

    @Override
    public Resources.Theme getTheme() {
        return theme == null?super.getTheme():theme;
    }
}
