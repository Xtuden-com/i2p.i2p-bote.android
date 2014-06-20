package i2p.bote.android;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import javax.mail.Flags.Flag;
import javax.mail.MessagingException;

import uk.co.senab.actionbarpulltorefresh.extras.actionbarcompat.PullToRefreshLayout;
import uk.co.senab.actionbarpulltorefresh.library.ActionBarPullToRefresh;
import uk.co.senab.actionbarpulltorefresh.library.Options;
import uk.co.senab.actionbarpulltorefresh.library.listeners.OnRefreshListener;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import i2p.bote.I2PBote;
import i2p.bote.android.util.BetterAsyncTaskLoader;
import i2p.bote.android.util.BoteHelper;
import i2p.bote.android.util.MoveToDialogFragment;
import i2p.bote.email.Email;
import i2p.bote.fileencryption.PasswordException;
import i2p.bote.folder.EmailFolder;
import i2p.bote.folder.FolderListener;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.view.ActionMode;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

public class EmailListFragment extends ListFragment implements
        LoaderManager.LoaderCallbacks<List<Email>>,
        MoveToDialogFragment.MoveToDialogListener,
        EmailListAdapter.EmailSelector, OnRefreshListener {
    public static final String FOLDER_NAME = "folder_name";

    private static final int EMAIL_LIST_LOADER = 1;

    OnEmailSelectedListener mCallback;

    private PullToRefreshLayout mPullToRefreshLayout;

    private EmailListAdapter mAdapter;
    private EmailFolder mFolder;
    private ActionMode mMode;

    private EditText mPasswordInput;
    private TextView mPasswordError;

    public static EmailListFragment newInstance(String folderName) {
        EmailListFragment f = new EmailListFragment();
        Bundle args = new Bundle();
        args.putString(FOLDER_NAME, folderName);
        f.setArguments(args);
        return f;
    }

    // Container Activity must implement this interface
    public interface OnEmailSelectedListener {
        public void onEmailSelected(String folderName, String messageId);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        // This makes sure that the container activity has implemented
        // the callback interface. If not, it throws an exception
        try {
            mCallback = (OnEmailSelectedListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement OnEmailSelectedListener");
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view,savedInstanceState);
        String folderName = getArguments().getString(FOLDER_NAME);
        mFolder = BoteHelper.getMailFolder(folderName);

        if (BoteHelper.isInbox(mFolder)) {
            // This is the View which is created by ListFragment
            ViewGroup viewGroup = (ViewGroup) view;

            // We need to create a PullToRefreshLayout manually
            mPullToRefreshLayout = new PullToRefreshLayout(viewGroup.getContext());

            // We can now setup the PullToRefreshLayout
            ActionBarPullToRefresh.from(getActivity())

                    // We need to insert the PullToRefreshLayout into the Fragment's ViewGroup
                    .insertLayoutInto(viewGroup)

                            // We need to mark the ListView and it's Empty View as pullable
                            // This is because they are not dirent children of the ViewGroup
                    .theseChildrenArePullable(getListView(), getListView().getEmptyView())

                            // We can now complete the setup as desired
                    .listener(this)
                    .options(Options.create()
                            .refreshOnUp(true)
                            .build())
                    .setup(mPullToRefreshLayout);

            mPullToRefreshLayout.setRefreshing(I2PBote.getInstance().isCheckingForMail());
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mAdapter = new EmailListAdapter(getActivity(), this,
                BoteHelper.isOutbox(mFolder));

        setListAdapter(mAdapter);

        // Set up CAB
        mMode = null;
        getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view,
                    int position, long id) {
                onListItemSelect(position);
                return true;
            }
        });

        if (mFolder == null) {
            setEmptyText(getResources().getString(
                    R.string.folder_does_not_exist));
            getActivity().setTitle(getResources().getString(R.string.app_name));
        } else {
            getActivity().setTitle(
                    BoteHelper.getFolderDisplayName(getActivity(), mFolder));
            if (I2PBote.getInstance().isPasswordRequired()) {
                // Request a password from the user.
                requestPassword();
            } else {
                // Password is cached, or not set.
                initializeList();
            }
        }
    }

    /**
     * Request the password from the user, and try it.
     */
    private void requestPassword() {
        LayoutInflater li = LayoutInflater.from(getActivity());
        View promptView = li.inflate(R.layout.dialog_password, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setView(promptView);
        mPasswordInput = (EditText) promptView.findViewById(R.id.passwordInput);
        mPasswordError = (TextView) promptView.findViewById(R.id.passwordError);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                InputMethodManager imm = (InputMethodManager) EmailListFragment.this
                        .getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(mPasswordInput.getWindowToken(), 0);
                dialog.dismiss();
                new PasswordWaiter().execute();
            }
        }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                setEmptyText(getResources().getString(
                        R.string.not_authed));
                dialog.cancel();
            }
        });
        AlertDialog passwordDialog = builder.create();
        passwordDialog.show();
    }

    private class PasswordWaiter extends AsyncTask<Void, Void, String> {
        private final ProgressDialog dialog = new ProgressDialog(EmailListFragment.this.getActivity());

        protected void onPreExecute() {
            dialog.setMessage(getResources().getString(
                    R.string.checking_password));
            dialog.setCancelable(false);
            dialog.show();
        }

        protected String doInBackground(Void... params) {
            try {
                if (BoteHelper.tryPassword(mPasswordInput.getText().toString()))
                    return null;
                else {
                    cancel(false);
                    return getResources().getString(
                            R.string.password_incorrect);
                }
            } catch (IOException e) {
                cancel(false);
                return getResources().getString(
                        R.string.password_file_error);
            } catch (GeneralSecurityException e) {
                cancel(false);
                return getResources().getString(
                        R.string.password_file_error);
            }
        }

        protected void onCancelled(String result) {
            dialog.dismiss();
            requestPassword();
            mPasswordError.setText(result);
            mPasswordError.setVisibility(View.VISIBLE);
        }

        protected void onPostExecute(String result) {
            // Password is valid
            initializeList();
            dialog.dismiss();
        }
    }

    /**
     * Start loading the list of emails from this folder.
     * Only called when we have a password cached, or no
     * password is required.
     */
    private void initializeList() {
        setListShown(false);
        setEmptyText(getResources().getString(
                R.string.folder_empty));
        getLoaderManager().initLoader(EMAIL_LIST_LOADER, null, this);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.email_list, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.action_new_email:
            Intent nei = new Intent(getActivity(), NewEmailActivity.class);
            startActivity(nei);
            return true;

        default:
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onListItemClick(ListView parent, View view, int pos, long id) {
        super.onListItemClick(parent, view, pos, id);
        if (mMode == null) {
            mCallback.onEmailSelected(
                    mFolder.getName(), mAdapter.getItem(pos).getMessageID());
        } else
            onListItemSelect(pos);
    }

    private void onListItemSelect(int position) {
        mAdapter.toggleSelection(position);
        boolean hasCheckedElement = mAdapter.getSelectedCount() > 0;

        if (hasCheckedElement && mMode == null) {
            boolean unread = mAdapter.getItem(position).isUnread();
            mMode = ((ActionBarActivity) getActivity()).startSupportActionMode(new ModeCallback(unread));
        } else if (!hasCheckedElement && mMode != null) {
            mMode.finish();
        }

        if (mMode != null)
            mMode.setTitle(getResources().getString(
                    R.string.items_selected, mAdapter.getSelectedCount()));
    }

    private final class ModeCallback implements ActionMode.Callback {
        private boolean areUnread;

        public ModeCallback(boolean unread) {
            super();
            this.areUnread = unread;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            // Respond to clicks on the actions in the CAB
            switch (item.getItemId()) {
            case R.id.action_delete:
                SparseBooleanArray toDelete = mAdapter.getSelectedIds();
                for (int i = (toDelete.size() - 1); i >= 0; i--) {
                    if (toDelete.valueAt(i)) {
                        Email email = mAdapter.getItem(toDelete.keyAt(i));
                        // The Loader will update mAdapter
                        I2PBote.getInstance().deleteEmail(mFolder, email.getMessageID());
                    }
                }
                mode.finish();
                return true;
            case R.id.action_mark_read:
            case R.id.action_mark_unread:
                SparseBooleanArray selected = mAdapter.getSelectedIds();
                for (int i = (selected.size() - 1); i >= 0; i--) {
                    if (selected.valueAt(i)) {
                        Email email = mAdapter.getItem(selected.keyAt(i));
                        try {
                            // The Loader will update mAdapter
                            mFolder.setNew(email, !areUnread);
                        } catch (PasswordException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        } catch (GeneralSecurityException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    }
                }
                areUnread = !areUnread;
                mMode.invalidate();
                return true;
            case R.id.action_move_to:
                DialogFragment f = MoveToDialogFragment.newInstance(mFolder);
                f.show(getFragmentManager(), "moveTo");
                return true;
            default:
                return false;
            }
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // Inflate the menu for the CAB
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.email_list_context, menu);
            if (BoteHelper.isOutbox(mFolder)) {
                menu.findItem(R.id.action_mark_read).setVisible(false);
                menu.findItem(R.id.action_mark_unread).setVisible(false);
            }
            // Only allow moving from the trash
            // TODO change this when user folders are implemented
            if (!BoteHelper.isTrash(mFolder))
                menu.findItem(R.id.action_move_to).setVisible(false);
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            // Here you can make any necessary updates to the activity when
            // the CAB is removed.
            mAdapter.removeSelection();

            if (mode == mMode)
                mMode = null;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            // Here you can perform updates to the CAB due to
            // an invalidate() request
            if (!BoteHelper.isOutbox(mFolder)) {
                menu.findItem(R.id.action_mark_read).setVisible(areUnread);
                menu.findItem(R.id.action_mark_unread).setVisible(!areUnread);
            }
            return true;
        }
    }

    // Called by EmailListActivity.onFolderSelected()

    public void onFolderSelected(EmailFolder newFolder) {
        SparseBooleanArray toMove = mAdapter.getSelectedIds();
        for (int i = (toMove.size() - 1); i >= 0; i--) {
            if (toMove.valueAt(i)) {
                Email email = mAdapter.getItem(toMove.keyAt(i));
                mFolder.move(email, newFolder);
            }
        }
        mMode.finish();
    }

    // LoaderManager.LoaderCallbacks<List<Email>>

    public Loader<List<Email>> onCreateLoader(int id, Bundle args) {
        return new EmailListLoader(getActivity(), mFolder);
    }

    private static class EmailListLoader extends BetterAsyncTaskLoader<List<Email>> implements
            FolderListener {
        private EmailFolder mFolder;

        public EmailListLoader(Context context, EmailFolder folder) {
            super(context);
            mFolder = folder;
        }

        @Override
        public List<Email> loadInBackground() {
            List<Email> emails = null;
            try {
                emails = BoteHelper.getEmails(mFolder, null, true);
            } catch (PasswordException pe) {
                // XXX: Should not get here.
            }
            return emails;
        }

        protected void onStartMonitoring() {
            mFolder.addFolderListener(this);
        }

        protected void onStopMonitoring() {
            mFolder.removeFolderListener(this);
        }

        protected void releaseResources(List<Email> data) {
        }

        // FolderListener

        @Override
        public void elementAdded(String messageId) {
            onContentChanged();
        }

        @Override
        public void elementUpdated() {
            onContentChanged();
        }

        @Override
        public void elementRemoved(String messageId) {
            onContentChanged();
        }
    }

    public void onLoadFinished(Loader<List<Email>> loader,
            List<Email> data) {
        // Clear recent flags
        for (Email email : data)
            try {
                email.setFlag(Flag.RECENT, false);
            } catch (MessagingException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        mAdapter.setData(data);
        try {
            getActivity().setTitle(
                    BoteHelper.getFolderDisplayNameWithNew(getActivity(), mFolder));
        } catch (PasswordException e) {
            // Should not get here.
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(EmailListFragment.class);
            if (log.shouldLog(Log.WARN))
                log.warn("Email list loader finished, but password is no longer cached", e);
        }

        if (isResumed()) {
            setListShown(true);
        } else {
            setListShownNoAnimation(true);
        }
    }

    public void onLoaderReset(Loader<List<Email>> loader) {
        mAdapter.setData(null);
        getActivity().setTitle(
                BoteHelper.getFolderDisplayName(getActivity(), mFolder));
    }

    // EmailListAdapter.EmailSelector

    public void select(int position) {
        onListItemSelect(position);
    }

    // OnRefreshListener

    public void onRefreshStarted(View view) {
        I2PBote bote = I2PBote.getInstance();
        if (bote.isConnected()) {
            try {
                if (!bote.isCheckingForMail())
                    bote.checkForMail();

                new AsyncTask<Void, Void, Void>() {
                    @Override
                    protected Void doInBackground(Void... params) {
                        while (I2PBote.getInstance().isCheckingForMail()) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {}
                        }
                        return null;
                    }

                    @Override
                    protected void onPostExecute(Void result) {
                        super.onPostExecute(result);

                        // Notify PullToRefreshLayout that the refresh has finished
                        mPullToRefreshLayout.setRefreshComplete();
                    }
                }.execute();
            } catch (PasswordException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (GeneralSecurityException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else
            mPullToRefreshLayout.setRefreshComplete();
    }
}