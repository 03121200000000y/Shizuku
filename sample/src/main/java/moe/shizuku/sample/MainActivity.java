package moe.shizuku.sample;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import moe.shizuku.api.ShizukuApiConstants;
import moe.shizuku.api.ShizukuBinderWrapper;
import moe.shizuku.api.ShizukuService;
import moe.shizuku.sample.databinding.MainActivityBinding;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class MainActivity extends Activity {

    private static final int REQUEST_CODE_BUTTON1 = 1;
    private static final int REQUEST_CODE_BUTTON2 = 2;
    private static final int REQUEST_CODE_BUTTON3 = 3;
    private static final int REQUEST_CODE_PICK_APKS = 10;

    private MainActivityBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d("ShizukuSample", getClass().getSimpleName() + " onCreate | Process=" + ApplicationUtils.getProcessName());

        super.onCreate(savedInstanceState);

        binding = MainActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.text1.setText("Waiting for binder");
        binding.text2.setText("This Activity is running in process \"" + ApplicationUtils.getProcessName() + "\"");
        binding.button1.setOnClickListener((v) -> {
            if (checkPermission()) getUsers();
        });
        binding.button2.setOnClickListener((v) -> {
            if (checkPermission()) installApks();
        });
        binding.button3.setOnClickListener((v) -> {
            if (checkPermission()) abandonMySessions();
        });

        ShizukuProvider.addBinderReceivedListenerSticky(() -> binding.text1.setText("Binder received"));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PERMISSION_GRANTED) {
            switch (requestCode) {
                case REQUEST_CODE_BUTTON1: {
                    getUsers();
                    break;
                }
                case REQUEST_CODE_BUTTON2: {
                    installApks();
                    break;
                }
                case REQUEST_CODE_BUTTON3: {
                    abandonMySessions();
                    break;
                }
            }
        } else {
            binding.text1.setText("User denied permission");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_PICK_APKS && resultCode == RESULT_OK) {
            List<Uri> uris;
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                uris = new ArrayList<>(clipData.getItemCount());
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    Uri uri = clipData.getItemAt(i).getUri();
                    if (uri != null) {
                        uris.add(uri);
                    }
                }
            } else {
                uris = new ArrayList<>();
                uris.add(data.getData());
            }
            doInstallApks(uris);
        }
    }

    private boolean checkPermission() {
        // Shizuku uses runtime permission, learn more https://developer.android.com/training/permissions/requesting
        if (checkSelfPermission(ShizukuApiConstants.PERMISSION) == PERMISSION_GRANTED) {
            return true;
        } else if (shouldShowRequestPermissionRationale(ShizukuApiConstants.PERMISSION)) {
            binding.text3.setText("User denied permission (shouldShowRequestPermissionRationale=true)");
            return false;
        } else {
            requestPermissions(new String[]{ShizukuApiConstants.PERMISSION}, REQUEST_CODE_BUTTON1);
            return false;
        }
    }

    private void getUsers() {
        String res;
        try {
            res = ShizukuApi.UserManager_getUsers(true).toString();
        } catch (Throwable tr) {
            res = tr.getMessage();
        }
        binding.text3.setText(res);
    }

    private void installApks() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.setType("application/vnd.android.package-archive");

        startActivityForResult(intent, REQUEST_CODE_PICK_APKS);
    }

    private void doInstallApks(List<Uri> uris) {
        PackageInstaller packageInstaller;
        PackageInstaller.Session session = null;
        ContentResolver cr = getContentResolver();
        StringBuilder res = new StringBuilder();
        String installerPackageName;
        int userId;
        boolean isRoot;

        try {
            IPackageInstaller _packageInstaller = ShizukuApi.PackageManager_getPackageInstaller();
            isRoot = ShizukuService.getUid() == 0;

            installerPackageName = isRoot ? getPackageName() : "com.android.shell";
            userId = isRoot ? Process.myUserHandle().hashCode() : 0;
            packageInstaller = PackageInstallerUtils.createPackageInstaller(_packageInstaller, installerPackageName, userId);

            int sessionId;
            res.append("createSession: ");

            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int installFlags = PackageInstallerUtils.getInstallFlags(params);
            installFlags |= 0x00000004/*PackageManager.INSTALL_ALLOW_TEST*/ | 0x00000002/*PackageManager.INSTALL_REPLACE_EXISTING*/;
            PackageInstallerUtils.setInstallFlags(params, installFlags);

            sessionId = packageInstaller.createSession(params);
            res.append(sessionId).append('\n');

            res.append('\n').append("write: ");

            IPackageInstallerSession _session = IPackageInstallerSession.Stub.asInterface(new ShizukuBinderWrapper(_packageInstaller.openSession(sessionId).asBinder()));
            session = PackageInstallerUtils.createSession(_session);

            int i = 0;
            for (Uri uri : uris) {
                String name = i + ".apk";

                InputStream is = cr.openInputStream(uri);
                OutputStream os = session.openWrite(name, 0, -1);

                byte[] buf = new byte[8192];
                int len;
                try {
                    while ((len = is.read(buf)) > 0) {
                        os.write(buf, 0, len);
                        os.flush();
                        session.fsync(os);
                    }
                } finally {
                    try {
                        is.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    try {
                        os.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                i++;

                Thread.sleep(1000);
            }

            res.append('\n').append("commit: ");

            Intent[] results = new Intent[]{null};
            CountDownLatch countDownLatch = new CountDownLatch(1);
            IntentSender intentSender = IntentSenderUtils.newInstance(new IIntentSenderAdaptor() {
                @Override
                public void send(Intent intent) {
                    results[0] = intent;
                    countDownLatch.countDown();
                }
            });
            session.commit(intentSender);

            countDownLatch.await();
            Intent result = results[0];
            int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            res.append('\n').append("status: ").append(status).append(" (").append(message).append(")");

        } catch (Throwable tr) {
            tr.printStackTrace();
            res.append(tr);
        } finally {
            if (session != null) {
                try {
                    session.close();

                } catch (Throwable tr) {
                    res.append(tr);
                }
            }
        }

        binding.text3.setText(res.toString().trim());
    }

    private void abandonMySessions() {
        StringBuilder res = new StringBuilder();
        String installer;
        int userId;
        boolean isRoot;
        IPackageInstaller packageInstaller;

        try {
            packageInstaller = ShizukuApi.PackageManager_getPackageInstaller();
            isRoot = ShizukuService.getUid() == 0;

            installer = isRoot ? getPackageName() : "com.android.shell";
            userId = isRoot ? android.os.Process.myUserHandle().hashCode() : 0;

            List<PackageInstaller.SessionInfo> sessions;
            res.append("abandonMySessions: ");
            sessions = packageInstaller.getMySessions(installer, userId).getList();
            for (PackageInstaller.SessionInfo session : sessions) {
                res.append(session.getSessionId());
                packageInstaller.abandonSession(session.getSessionId());
                res.append(" (abandoned)\n");
            }
        } catch (Throwable tr) {
            res.append(tr);
        }

        binding.text3.setText(res.toString().trim());
    }
}
