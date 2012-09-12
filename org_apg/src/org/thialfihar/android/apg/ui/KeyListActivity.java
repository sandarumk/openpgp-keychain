/*
 * Copyright (C) 2012 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2010 Thialfihar <thi@thialfihar.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.thialfihar.android.apg.ui;

import org.thialfihar.android.apg.Constants;
import org.thialfihar.android.apg.Id;
import org.thialfihar.android.apg.helper.OtherHelper;
import org.thialfihar.android.apg.helper.PGPHelper;
import org.thialfihar.android.apg.helper.PGPMain;
import org.thialfihar.android.apg.provider.KeyRings;
import org.thialfihar.android.apg.provider.Keys;
import org.thialfihar.android.apg.provider.UserIds;
import org.thialfihar.android.apg.service.ApgServiceHandler;
import org.thialfihar.android.apg.service.ApgService;
import org.thialfihar.android.apg.ui.dialog.DeleteFileDialogFragment;
import org.thialfihar.android.apg.ui.dialog.DeleteKeyDialogFragment;
import org.thialfihar.android.apg.ui.dialog.FileDialogFragment;
import org.thialfihar.android.apg.R;

import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.MenuItem;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import org.thialfihar.android.apg.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.ExpandableListView.ExpandableListContextMenuInfo;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Vector;

public class KeyListActivity extends SherlockFragmentActivity {

    public static final String ACTION_IMPORT = Constants.INTENT_PREFIX + "IMPORT";

    public static final String EXTRA_TEXT = "text";

    protected ExpandableListView mList;
    protected KeyListAdapter mListAdapter;
    protected View mFilterLayout;
    protected Button mClearFilterButton;
    protected TextView mFilterInfo;

    protected int mSelectedItem = -1;
    protected int mTask = 0;

    protected String mImportFilename = Constants.path.APP_DIR + "/";
    protected String mExportFilename = Constants.path.APP_DIR + "/";

    protected String mImportData;
    protected boolean mDeleteAfterImport = false;

    protected int mKeyType = Id.type.public_key;

    FileDialogFragment mFileDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.key_list);

        // set actionbar without home button if called from another app
        OtherHelper.setActionBarBackButton(this);

        setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);

        mList = (ExpandableListView) findViewById(R.id.list);
        registerForContextMenu(mList);

        mFilterLayout = findViewById(R.id.layout_filter);
        mFilterInfo = (TextView) mFilterLayout.findViewById(R.id.filterInfo);
        mClearFilterButton = (Button) mFilterLayout.findViewById(R.id.btn_clear);

        mClearFilterButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                handleIntent(new Intent());
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    protected void handleIntent(Intent intent) {
        String searchString = null;
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            searchString = intent.getStringExtra(SearchManager.QUERY);
            if (searchString != null && searchString.trim().length() == 0) {
                searchString = null;
            }
        }

        if (searchString == null) {
            mFilterLayout.setVisibility(View.GONE);
        } else {
            mFilterLayout.setVisibility(View.VISIBLE);
            mFilterInfo.setText(getString(R.string.filterInfo, searchString));
        }

        if (mListAdapter != null) {
            mListAdapter.cleanup();
        }
        mListAdapter = new KeyListAdapter(this, searchString);
        mList.setAdapter(mListAdapter);

        // Get intent, action
        // Intent intent = getIntent();
        String action = intent.getAction();

        if (Intent.ACTION_VIEW.equals(action)) {
            // Android's Action when opening file associated to APG (see AndroidManifest.xml)

            handleActionImport(intent);
        } else if (ACTION_IMPORT.equals(action)) {
            // APG's own Actions

            handleActionImport(intent);
        }
    }

    /**
     * Handles import action
     * 
     * @param intent
     */
    private void handleActionImport(Intent intent) {
        if ("file".equals(intent.getScheme()) && intent.getDataString() != null) {
            mImportFilename = intent.getData().getPath();
        } else {
            mImportData = intent.getStringExtra(EXTRA_TEXT);
        }
        importKeys();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

        case android.R.id.home:
            // app icon in Action Bar clicked; go home
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            return true;

        case Id.menu.option.import_keys: {
            showImportKeysDialog();
            return true;
        }

        case Id.menu.option.export_keys: {
            showExportKeysDialog(false);
            return true;
        }

        case Id.menu.option.search:
            startSearch("", false, null, false);
            return true;

        default: {
            return super.onOptionsItemSelected(item);
        }
        }
    }

    private void showImportKeysDialog() {
        // Message is received after file is selected
        Handler returnHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                if (message.what == FileDialogFragment.MESSAGE_OKAY) {
                    Bundle data = message.getData();
                    mImportFilename = data.getString(FileDialogFragment.MESSAGE_DATA_FILENAME);

                    mDeleteAfterImport = data.getBoolean(FileDialogFragment.MESSAGE_DATA_CHECKED);
                    importKeys();
                }
            }
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(returnHandler);

        mFileDialog = FileDialogFragment.newInstance(messenger,
                getString(R.string.title_importKeys), getString(R.string.specifyFileToImportFrom),
                mImportFilename, null, Id.request.filename);

        mFileDialog.show(getSupportFragmentManager(), "fileDialog");
    }

    private void showExportKeysDialog(boolean singleKeyExport) {
        String title = (singleKeyExport ? getString(R.string.title_exportKey)
                : getString(R.string.title_exportKeys));
        String message = getString(mKeyType == Id.type.public_key ? R.string.specifyFileToExportTo
                : R.string.specifyFileToExportSecretKeysTo);

        // Message is received after file is selected
        Handler returnHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                if (message.what == FileDialogFragment.MESSAGE_OKAY) {
                    Bundle data = message.getData();
                    mExportFilename = data.getString(FileDialogFragment.MESSAGE_DATA_FILENAME);

                    exportKeys();
                }
            }
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(returnHandler);

        mFileDialog = FileDialogFragment.newInstance(messenger, title, message, mExportFilename,
                null, Id.request.filename);

        mFileDialog.show(getSupportFragmentManager(), "fileDialog");
    }

    @Override
    public boolean onContextItemSelected(android.view.MenuItem menuItem) {
        ExpandableListContextMenuInfo info = (ExpandableListContextMenuInfo) menuItem.getMenuInfo();
        int type = ExpandableListView.getPackedPositionType(info.packedPosition);
        int groupPosition = ExpandableListView.getPackedPositionGroup(info.packedPosition);

        if (type != ExpandableListView.PACKED_POSITION_TYPE_GROUP) {
            return super.onContextItemSelected(menuItem);
        }

        switch (menuItem.getItemId()) {
        case Id.menu.export: {
            mSelectedItem = groupPosition;
            showExportKeysDialog(true);
            return true;
        }

        case Id.menu.delete: {
            mSelectedItem = groupPosition;
            showDeleteKeyDialog();
            return true;
        }

        default: {
            return super.onContextItemSelected(menuItem);
        }
        }
    }

    private void showDeleteKeyDialog() {
        final int keyRingId = mListAdapter.getKeyRingId(mSelectedItem);
        mSelectedItem = -1;

        // Message is received after key is deleted
        Handler returnHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                if (message.what == DeleteKeyDialogFragment.MESSAGE_OKAY) {
                    refreshList();
                }
            }
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(returnHandler);

        DeleteKeyDialogFragment deleteKeyDialog = DeleteKeyDialogFragment.newInstance(messenger,
                keyRingId, mKeyType);

        deleteKeyDialog.show(getSupportFragmentManager(), "deleteKeyDialog");
    }

    public void importKeys() {
        Log.d(Constants.TAG, "importKeys started");

        // Send all information needed to service to import key in other thread
        Intent intent = new Intent(this, ApgService.class);

        intent.putExtra(ApgService.EXTRA_ACTION, ApgService.ACTION_IMPORT_KEY);

        // fill values for this action
        Bundle data = new Bundle();

        data.putInt(ApgService.IMPORT_KEY_TYPE, mKeyType);

        if (mImportData != null) {
            data.putInt(ApgService.TARGET, ApgService.TARGET_BYTES);
            data.putByteArray(ApgService.IMPORT_BYTES, mImportData.getBytes());
        } else {
            data.putInt(ApgService.TARGET, ApgService.TARGET_FILE);
            data.putString(ApgService.IMPORT_FILENAME, mImportFilename);
        }

        intent.putExtra(ApgService.EXTRA_DATA, data);

        // Message is received after importing is done in ApgService
        ApgServiceHandler saveHandler = new ApgServiceHandler(this, R.string.progress_importing,
                ProgressDialog.STYLE_HORIZONTAL) {
            public void handleMessage(Message message) {
                // handle messages by standard ApgHandler first
                super.handleMessage(message);

                if (message.arg1 == ApgServiceHandler.MESSAGE_OKAY) {
                    // get returned data bundle
                    Bundle returnData = message.getData();

                    int added = returnData.getInt(ApgService.RESULT_IMPORT_ADDED);
                    int updated = returnData.getInt(ApgService.RESULT_IMPORT_UPDATED);
                    int bad = returnData.getInt(ApgService.RESULT_IMPORT_BAD);
                    String toastMessage;
                    if (added > 0 && updated > 0) {
                        toastMessage = getString(R.string.keysAddedAndUpdated, added, updated);
                    } else if (added > 0) {
                        toastMessage = getString(R.string.keysAdded, added);
                    } else if (updated > 0) {
                        toastMessage = getString(R.string.keysUpdated, updated);
                    } else {
                        toastMessage = getString(R.string.noKeysAddedOrUpdated);
                    }
                    Toast.makeText(KeyListActivity.this, toastMessage, Toast.LENGTH_SHORT).show();
                    if (bad > 0) {
                        AlertDialog.Builder alert = new AlertDialog.Builder(KeyListActivity.this);

                        alert.setIcon(android.R.drawable.ic_dialog_alert);
                        alert.setTitle(R.string.warning);
                        alert.setMessage(KeyListActivity.this.getString(
                                R.string.badKeysEncountered, bad));

                        alert.setPositiveButton(android.R.string.ok,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int id) {
                                        dialog.cancel();
                                    }
                                });
                        alert.setCancelable(true);
                        alert.create().show();
                    } else if (mDeleteAfterImport) {
                        // everything went well, so now delete, if that was turned on
                        DeleteFileDialogFragment deleteFileDialog = DeleteFileDialogFragment
                                .newInstance(mImportFilename);
                        deleteFileDialog.show(getSupportFragmentManager(), "deleteDialog");
                    }
                    refreshList();

                }
            };
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(saveHandler);
        intent.putExtra(ApgService.EXTRA_MESSENGER, messenger);

        // show progress dialog
        saveHandler.showProgressDialog(this);

        // start service with intent
        startService(intent);
    }

    public void exportKeys() {
        Log.d(Constants.TAG, "exportKeys started");

        // Send all information needed to service to export key in other thread
        Intent intent = new Intent(this, ApgService.class);

        intent.putExtra(ApgService.EXTRA_ACTION, ApgService.ACTION_EXPORT_KEY);

        // fill values for this action
        Bundle data = new Bundle();

        data.putString(ApgService.EXPORT_FILENAME, mExportFilename);
        data.putInt(ApgService.EXPORT_KEY_TYPE, mKeyType);

        if (mSelectedItem == -1) {
            data.putBoolean(ApgService.EXPORT_ALL, true);
        } else {
            int keyRingId = mListAdapter.getKeyRingId(mSelectedItem);
            data.putInt(ApgService.EXPORT_KEY_RING_ID, keyRingId);
            mSelectedItem = -1;
        }

        intent.putExtra(ApgService.EXTRA_DATA, data);

        // Message is received after exporting is done in ApgService
        ApgServiceHandler exportHandler = new ApgServiceHandler(this, R.string.progress_exporting,
                ProgressDialog.STYLE_HORIZONTAL) {
            public void handleMessage(Message message) {
                // handle messages by standard ApgHandler first
                super.handleMessage(message);

                if (message.arg1 == ApgServiceHandler.MESSAGE_OKAY) {
                    // get returned data bundle
                    Bundle returnData = message.getData();

                    int exported = returnData.getInt(ApgService.RESULT_EXPORT);
                    String toastMessage;
                    if (exported == 1) {
                        toastMessage = getString(R.string.keyExported);
                    } else if (exported > 0) {
                        toastMessage = getString(R.string.keysExported, exported);
                    } else {
                        toastMessage = getString(R.string.noKeysExported);
                    }
                    Toast.makeText(KeyListActivity.this, toastMessage, Toast.LENGTH_SHORT).show();

                }
            };
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(exportHandler);
        intent.putExtra(ApgService.EXTRA_MESSENGER, messenger);

        // show progress dialog
        exportHandler.showProgressDialog(this);

        // start service with intent
        startService(intent);
    }

    protected void refreshList() {
        mListAdapter.rebuild(true);
        mListAdapter.notifyDataSetChanged();
    }

    protected class KeyListAdapter extends BaseExpandableListAdapter {
        private LayoutInflater mInflater;
        private Vector<Vector<KeyChild>> mChildren;
        private SQLiteDatabase mDatabase;
        private Cursor mCursor;
        private String mSearchString;

        private class KeyChild {
            public static final int KEY = 0;
            public static final int USER_ID = 1;
            public static final int FINGER_PRINT = 2;

            public int type;
            public String userId;
            public long keyId;
            public boolean isMasterKey;
            public int algorithm;
            public int keySize;
            public boolean canSign;
            public boolean canEncrypt;
            public String fingerPrint;

            public KeyChild(long keyId, boolean isMasterKey, int algorithm, int keySize,
                    boolean canSign, boolean canEncrypt) {
                this.type = KEY;
                this.keyId = keyId;
                this.isMasterKey = isMasterKey;
                this.algorithm = algorithm;
                this.keySize = keySize;
                this.canSign = canSign;
                this.canEncrypt = canEncrypt;
            }

            public KeyChild(String userId) {
                type = USER_ID;
                this.userId = userId;
            }

            public KeyChild(String fingerPrint, boolean isFingerPrint) {
                type = FINGER_PRINT;
                this.fingerPrint = fingerPrint;
            }
        }

        public KeyListAdapter(Context context, String searchString) {
            mSearchString = searchString;

            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mDatabase = PGPMain.getDatabase().db();
            SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            qb.setTables(KeyRings.TABLE_NAME + " INNER JOIN " + Keys.TABLE_NAME + " ON " + "("
                    + KeyRings.TABLE_NAME + "." + KeyRings._ID + " = " + Keys.TABLE_NAME + "."
                    + Keys.KEY_RING_ID + " AND " + Keys.TABLE_NAME + "." + Keys.IS_MASTER_KEY
                    + " = '1'" + ") " + " INNER JOIN " + UserIds.TABLE_NAME + " ON " + "("
                    + Keys.TABLE_NAME + "." + Keys._ID + " = " + UserIds.TABLE_NAME + "."
                    + UserIds.KEY_ID + " AND " + UserIds.TABLE_NAME + "." + UserIds.RANK
                    + " = '0')");

            if (searchString != null && searchString.trim().length() > 0) {
                String[] chunks = searchString.trim().split(" +");
                qb.appendWhere("EXISTS (SELECT tmp." + UserIds._ID + " FROM " + UserIds.TABLE_NAME
                        + " AS tmp WHERE " + "tmp." + UserIds.KEY_ID + " = " + Keys.TABLE_NAME
                        + "." + Keys._ID);
                for (int i = 0; i < chunks.length; ++i) {
                    qb.appendWhere(" AND tmp." + UserIds.USER_ID + " LIKE ");
                    qb.appendWhereEscapeString("%" + chunks[i] + "%");
                }
                qb.appendWhere(")");
            }

            mCursor = qb.query(mDatabase, new String[] { KeyRings.TABLE_NAME + "." + KeyRings._ID, // 0
                    KeyRings.TABLE_NAME + "." + KeyRings.MASTER_KEY_ID, // 1
                    UserIds.TABLE_NAME + "." + UserIds.USER_ID, // 2
            }, KeyRings.TABLE_NAME + "." + KeyRings.TYPE + " = ?", new String[] { ""
                    + (mKeyType == Id.type.public_key ? Id.database.type_public
                            : Id.database.type_secret) }, null, null, UserIds.TABLE_NAME + "."
                    + UserIds.USER_ID + " ASC");

            // content provider way for reference, might have to go back to it sometime:
            /*
             * Uri contentUri = null; if (mKeyType == Id.type.secret_key) { contentUri =
             * Apg.CONTENT_URI_SECRET_KEY_RINGS; } else { contentUri =
             * Apg.CONTENT_URI_PUBLIC_KEY_RINGS; } mCursor = getContentResolver().query( contentUri,
             * new String[] { DataProvider._ID, // 0 DataProvider.MASTER_KEY_ID, // 1
             * DataProvider.USER_ID, // 2 }, null, null, null);
             */

            startManagingCursor(mCursor);
            rebuild(false);
        }

        public void cleanup() {
            if (mCursor != null) {
                stopManagingCursor(mCursor);
                mCursor.close();
            }
        }

        public void rebuild(boolean requery) {
            if (requery) {
                mCursor.requery();
            }
            mChildren = new Vector<Vector<KeyChild>>();
            for (int i = 0; i < mCursor.getCount(); ++i) {
                mChildren.add(null);
            }
        }

        protected Vector<KeyChild> getChildrenOfGroup(int groupPosition) {
            Vector<KeyChild> children = mChildren.get(groupPosition);
            if (children != null) {
                return children;
            }

            mCursor.moveToPosition(groupPosition);
            children = new Vector<KeyChild>();
            Cursor c = mDatabase.query(Keys.TABLE_NAME, new String[] { Keys._ID, // 0
                    Keys.KEY_ID, // 1
                    Keys.IS_MASTER_KEY, // 2
                    Keys.ALGORITHM, // 3
                    Keys.KEY_SIZE, // 4
                    Keys.CAN_SIGN, // 5
                    Keys.CAN_ENCRYPT, // 6
            }, Keys.KEY_RING_ID + " = ?", new String[] { mCursor.getString(0) }, null, null,
                    Keys.RANK + " ASC");

            int masterKeyId = -1;
            long fingerPrintId = -1;
            for (int i = 0; i < c.getCount(); ++i) {
                c.moveToPosition(i);
                children.add(new KeyChild(c.getLong(1), c.getInt(2) == 1, c.getInt(3), c.getInt(4),
                        c.getInt(5) == 1, c.getInt(6) == 1));
                if (i == 0) {
                    masterKeyId = c.getInt(0);
                    fingerPrintId = c.getLong(1);
                }
            }
            c.close();

            if (masterKeyId != -1) {
                children.insertElementAt(
                        new KeyChild(PGPHelper.getFingerPrint(fingerPrintId), true), 0);
                c = mDatabase.query(UserIds.TABLE_NAME, new String[] { UserIds.USER_ID, // 0
                        }, UserIds.KEY_ID + " = ? AND " + UserIds.RANK + " > 0", new String[] { ""
                                + masterKeyId }, null, null, UserIds.RANK + " ASC");

                for (int i = 0; i < c.getCount(); ++i) {
                    c.moveToPosition(i);
                    children.add(new KeyChild(c.getString(0)));
                }
                c.close();
            }

            mChildren.set(groupPosition, children);
            return children;
        }

        public boolean hasStableIds() {
            return true;
        }

        public boolean isChildSelectable(int groupPosition, int childPosition) {
            return true;
        }

        public int getGroupCount() {
            return mCursor.getCount();
        }

        public Object getChild(int groupPosition, int childPosition) {
            return null;
        }

        public long getChildId(int groupPosition, int childPosition) {
            return childPosition;
        }

        public int getChildrenCount(int groupPosition) {
            return getChildrenOfGroup(groupPosition).size();
        }

        public Object getGroup(int position) {
            return position;
        }

        public long getGroupId(int position) {
            mCursor.moveToPosition(position);
            return mCursor.getLong(1); // MASTER_KEY_ID
        }

        public int getKeyRingId(int position) {
            mCursor.moveToPosition(position);
            return mCursor.getInt(0); // _ID
        }

        public View getGroupView(int groupPosition, boolean isExpanded, View convertView,
                ViewGroup parent) {
            mCursor.moveToPosition(groupPosition);

            View view = mInflater.inflate(R.layout.key_list_group_item, null);
            view.setBackgroundResource(android.R.drawable.list_selector_background);

            TextView mainUserId = (TextView) view.findViewById(R.id.mainUserId);
            mainUserId.setText("");
            TextView mainUserIdRest = (TextView) view.findViewById(R.id.mainUserIdRest);
            mainUserIdRest.setText("");

            String userId = mCursor.getString(2); // USER_ID
            if (userId != null) {
                String chunks[] = userId.split(" <", 2);
                userId = chunks[0];
                if (chunks.length > 1) {
                    mainUserIdRest.setText("<" + chunks[1]);
                }
                mainUserId.setText(userId);
            }

            if (mainUserId.getText().length() == 0) {
                mainUserId.setText(R.string.unknownUserId);
            }

            if (mainUserIdRest.getText().length() == 0) {
                mainUserIdRest.setVisibility(View.GONE);
            }
            return view;
        }

        public View getChildView(int groupPosition, int childPosition, boolean isLastChild,
                View convertView, ViewGroup parent) {
            mCursor.moveToPosition(groupPosition);

            Vector<KeyChild> children = getChildrenOfGroup(groupPosition);

            KeyChild child = children.get(childPosition);
            View view = null;
            switch (child.type) {
            case KeyChild.KEY: {
                if (child.isMasterKey) {
                    view = mInflater.inflate(R.layout.key_list_child_item_master_key, null);
                } else {
                    view = mInflater.inflate(R.layout.key_list_child_item_sub_key, null);
                }

                TextView keyId = (TextView) view.findViewById(R.id.keyId);
                String keyIdStr = PGPHelper.getSmallFingerPrint(child.keyId);
                keyId.setText(keyIdStr);
                TextView keyDetails = (TextView) view.findViewById(R.id.keyDetails);
                String algorithmStr = PGPHelper.getAlgorithmInfo(child.algorithm, child.keySize);
                keyDetails.setText("(" + algorithmStr + ")");

                ImageView encryptIcon = (ImageView) view.findViewById(R.id.ic_encryptKey);
                if (!child.canEncrypt) {
                    encryptIcon.setVisibility(View.GONE);
                }

                ImageView signIcon = (ImageView) view.findViewById(R.id.ic_signKey);
                if (!child.canSign) {
                    signIcon.setVisibility(View.GONE);
                }
                break;
            }

            case KeyChild.USER_ID: {
                view = mInflater.inflate(R.layout.key_list_child_item_user_id, null);
                TextView userId = (TextView) view.findViewById(R.id.userId);
                userId.setText(child.userId);
                break;
            }

            case KeyChild.FINGER_PRINT: {
                view = mInflater.inflate(R.layout.key_list_child_item_user_id, null);
                TextView userId = (TextView) view.findViewById(R.id.userId);
                userId.setText(getString(R.string.fingerprint) + ":\n"
                        + child.fingerPrint.replace("  ", "\n"));
                break;
            }
            }
            return view;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
        case Id.request.filename: {
            if (resultCode == RESULT_OK && data != null) {
                try {
                    String path = data.getData().getPath();
                    Log.d(Constants.TAG, "path=" + path);

                    mFileDialog.setFilename(path);
                } catch (NullPointerException e) {
                    Log.e(Constants.TAG, "Nullpointer while retrieving path!", e);
                }
            }
            return;
        }

        default: {
            break;
        }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
