package ca.pkay.rcloneexplorer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.OpenableColumns;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import ca.pkay.rcloneexplorer.Dialogs.Dialogs;
import ca.pkay.rcloneexplorer.Dialogs.LoadingDialog;
import ca.pkay.rcloneexplorer.Fragments.ShareFragment;
import ca.pkay.rcloneexplorer.Fragments.ShareRemotesFragment;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Services.UploadService;
import ca.pkay.rcloneexplorer.util.FLog;
import es.dmoral.toasty.Toasty;
import static ca.pkay.rcloneexplorer.ActivityHelper.tryStartService;

public class SharingActivity extends AppCompatActivity implements   ShareRemotesFragment.OnRemoteClickListener,
                                                                    ShareFragment.OnShareDestinationSelected {

    private boolean isDarkTheme;
    private Fragment fragment;
    private ArrayList<String> uploadList;
    private boolean isDataReady;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.applyTheme(this);
        isDarkTheme = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(getString(R.string.pref_key_dark_theme), false);
        setContentView(R.layout.activity_sharing);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Rclone rclone = new Rclone(this);
        uploadList = new ArrayList<>();

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            copyFile(intent);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            copyFiles(intent);
        } else {
            finish();
            return;
        }

        if (rclone.isConfigEncrypted() || !rclone.isConfigFileCreated() || rclone.getRemotes().isEmpty()) {
            AlertDialog.Builder builder;
            if (isDarkTheme) {
                builder = new AlertDialog.Builder(this, R.style.DarkDialogTheme);
            } else {
                builder = new AlertDialog.Builder(this);
            }
            builder
                    .setTitle(R.string.app_not_configured)
                    .setMessage(R.string.open_app_to_configure)
                    .setPositiveButton(R.string.ok, (dialog, which) -> finish())
                    .show();
        } else {
            startRemotesFragment();
        }
    }

    @Override
    public void onBackPressed() {
        if (fragment != null && fragment instanceof ShareFragment) {
            if (((ShareFragment)fragment).onBackButtonPressed()) {
                return;
            }
        }
        super.onBackPressed();
    }

    private void startRemotesFragment() {
        fragment = ShareRemotesFragment.newInstance();
        FragmentManager fragmentManager = getSupportFragmentManager();

        for (int i = 0; i < fragmentManager.getBackStackEntryCount(); i++) {
            fragmentManager.popBackStack();
        }

        fragmentManager.beginTransaction().replace(R.id.flFragment, fragment).commit();
    }

    @Override
    public void onRemoteClick(RemoteItem remote) {
        startRemote(remote);
    }

    private void startRemote(RemoteItem remoteItem) {
        fragment = ShareFragment.newInstance(remoteItem);
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.flFragment, fragment);
        transaction.addToBackStack(null);
        transaction.commit();
    }

    @Override
    public void onShareDestinationSelected(RemoteItem remote, String path) {
        new UploadTask(this, remote, path).execute();
    }

    private void copyFile(Intent intent) {
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            finish();
        }
        isDataReady = false;
        new CopyFile(this, uri).execute();
    }

    private void copyFiles(Intent intent) {
        ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (uris == null) {
            finish();
        }
        isDataReady = false;
        new CopyFile(this, uris).execute();
    }


    @SuppressLint("StaticFieldLeak")
    private class UploadTask extends AsyncTask<Void, Void, Void> {

        RemoteItem remote;
        String path;
        Context context;
        LoadingDialog loadingDialog;

        UploadTask(Context context, RemoteItem remote, String path) {
            this.context = context;
            this.remote = remote;
            this.path = path;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingDialog = new LoadingDialog()
                    .setTitle(R.string.loading)
                    .setDarkTheme(isDarkTheme)
                    .setNegativeButton(R.string.cancel)
                    .setOnNegativeListener(() -> cancel(true));
            loadingDialog.show(getSupportFragmentManager(), "loading dialog");
        }

        @Override
        protected Void doInBackground(Void... voids) {
            while (!isDataReady) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            Dialogs.dismissSilently(loadingDialog);

            for (String uploadFile : uploadList) {
                Intent intent = new Intent(context, UploadService.class);
                intent.putExtra(UploadService.LOCAL_PATH_ARG, uploadFile);
                intent.putExtra(UploadService.UPLOAD_PATH_ARG, path);
                intent.putExtra(UploadService.REMOTE_ARG, remote);
                tryStartService(context, intent);
            }
            finish();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class CopyFile extends AsyncTask<Void, Void, Boolean> {

        private static final String TAG = "SharingActvty/CopyFile";
        private Context context;
        private ArrayList<Uri> uris;

        CopyFile(Context context, Uri uri) {
            this.context = context;
            uris = new ArrayList<>();
            uris.add(uri);
        }

        CopyFile(Context context, ArrayList<Uri> uris) {
            this.context = context;
            this.uris = uris;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            boolean success = true;
            for (Uri uri : uris) {
                String fileName;
                if (null == uri.getScheme() || null == uri.getPath()) {
                    FLog.w(TAG, "Can't copy invalid uri %s", uri);
                    success = false;
                    continue;
                }
                if (uri.getScheme().equals("content")) {
                    Cursor returnCursor = getContentResolver().query(uri, null, null, null, null);
                    if (returnCursor == null) {
                        return false;
                    }
                    int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    returnCursor.moveToFirst();
                    fileName = returnCursor.getString(nameIndex);
                    returnCursor.close();
                } else {
                    fileName = uri.getPath();
                    int index = fileName.lastIndexOf("/");
                    fileName = fileName.substring(index + 1);
                }

                File cacheDir = getExternalCacheDir();
                InputStream inputStream;
                try {
                    inputStream = getContentResolver().openInputStream(uri);
                    if (inputStream == null) {
                        return false;
                    }
                    File outFile = new File(cacheDir, fileName);
                    uploadList.add(outFile.getAbsolutePath());
                    FileOutputStream fileOutputStream = new FileOutputStream(outFile);
                    byte[] buffer = new byte[4096];
                    int offset;
                    while ((offset = inputStream.read(buffer)) > 0) {
                        fileOutputStream.write(buffer, 0, offset);
                    }
                    inputStream.close();
                    fileOutputStream.flush();
                    fileOutputStream.close();
                } catch (IOException e) {
                    FLog.e(TAG, "Copy error ", e);
                    return false;
                }
            }
            return success;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            if (!success) {
                Toasty.error(context, getString(R.string.error_retrieving_files), Toast.LENGTH_LONG, true).show();
                finish();
            }
            isDataReady = true;
        }
    }
}
