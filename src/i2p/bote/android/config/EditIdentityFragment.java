package i2p.bote.android.config;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import i2p.bote.I2PBote;
import i2p.bote.android.R;
import i2p.bote.android.util.BoteHelper;
import i2p.bote.android.util.EditPictureFragment;
import i2p.bote.android.util.RobustAsyncTask;
import i2p.bote.android.util.TaskFragment;
import i2p.bote.StatusListener;
import i2p.bote.crypto.CryptoFactory;
import i2p.bote.crypto.CryptoImplementation;
import i2p.bote.email.EmailIdentity;
import i2p.bote.fileencryption.PasswordException;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

public class EditIdentityFragment extends EditPictureFragment {
    private Callbacks mCallbacks = sDummyCallbacks;

    public interface Callbacks {
        public void onTaskFinished();
    }
    private static Callbacks sDummyCallbacks = new Callbacks() {
        public void onTaskFinished() {};
    };

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (!(activity instanceof Callbacks))
            throw new IllegalStateException("Activity must implement fragment's callbacks.");
        mCallbacks = (Callbacks) activity;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mCallbacks = sDummyCallbacks;
    }

    public static final String IDENTITY_KEY = "identity_key";

    // Code to identify the fragment that is calling onActivityResult().
    static final int IDENTITY_WAITER = 0;
    // Tag so we can find the task fragment again, in another
    // instance of this fragment after rotation.
    static final String IDENTITY_WAITER_TAG = "identityWaiterTask";

    static final int DEFAULT_CRYPTO_IMPL = 2;

    private String mKey;
    private FragmentManager mFM;
    MenuItem mSave;
    EditText mNameField;
    EditText mDescField;
    Spinner mCryptoField;
    int mDefaultPos;
    CheckBox mDefaultField;
    TextView mError;

    public static EditIdentityFragment newInstance(String key) {
        EditIdentityFragment f = new EditIdentityFragment();
        Bundle args = new Bundle();
        args.putString(IDENTITY_KEY, key);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        mFM = getFragmentManager();
        IdentityWaiterFrag f = (IdentityWaiterFrag) mFM.findFragmentByTag(IDENTITY_WAITER_TAG);
        if (f != null)
            f.setTargetFragment(this, IDENTITY_WAITER);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_edit_identity, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mKey = getArguments().getString(IDENTITY_KEY);

        mNameField = (EditText) view.findViewById(R.id.public_name);
        mDescField = (EditText) view.findViewById(R.id.description);
        mDefaultField = (CheckBox) view.findViewById(R.id.default_identity);
        mError = (TextView) view.findViewById(R.id.error);

        if (mKey == null) {
            // Show the encryption choice field
            mCryptoField = (Spinner) view.findViewById(R.id.crypto_impl);
            CryptoAdapter adapter = new CryptoAdapter(getActivity());
            mCryptoField.setAdapter(adapter);
            mCryptoField.setSelection(mDefaultPos);
            mCryptoField.setVisibility(View.VISIBLE);
        } else {
            // Load the identity to edit
            try {
                EmailIdentity identity = BoteHelper.getIdentity(mKey);

                String pic = identity.getPictureBase64();
                if (pic != null && !pic.isEmpty()) {
                    setPictureB64(pic);
                }

                mNameField.setText(identity.getPublicName());
                mDescField.setText(identity.getDescription());
                mDefaultField.setChecked(identity.isDefault());
            } catch (PasswordException e) {
                // TODO Handle
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Handle
                e.printStackTrace();
            } catch (GeneralSecurityException e) {
                // TODO Handle
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.edit_identity, menu);
        mSave = menu.findItem(R.id.action_save_identity);

        IdentityWaiterFrag f = (IdentityWaiterFrag) mFM.findFragmentByTag(IDENTITY_WAITER_TAG);
        if (f != null)
            setInterfaceEnabled(false);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.action_save_identity:
            String picture = getPictureB64();
            String publicName = mNameField.getText().toString();
            String description = mDescField.getText().toString();
            boolean setDefault = mDefaultField.isChecked();

            int cryptoImplId = -1;
            if (mKey == null)
                cryptoImplId = ((CryptoImplementation) mCryptoField.getSelectedItem()).getId();

            InputMethodManager imm = (InputMethodManager)getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(mNameField.getWindowToken(), 0);

            setInterfaceEnabled(false);
            mError.setText("");

            IdentityWaiterFrag f = IdentityWaiterFrag.newInstance(
                    (mKey == null ? true : false),
                    cryptoImplId,
                    mKey,
                    publicName,
                    description,
                    null,
                    setDefault);
            f.setTask(new IdentityWaiter());
            f.setTargetFragment(EditIdentityFragment.this, IDENTITY_WAITER);
            mFM.beginTransaction()
            .replace(R.id.identity_waiter_frag, f, IDENTITY_WAITER_TAG)
            .commit();
            return true;

        default:
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == IDENTITY_WAITER) {
            if (resultCode == Activity.RESULT_OK) {
                mCallbacks.onTaskFinished();
            } else if (resultCode == Activity.RESULT_CANCELED) {
                setInterfaceEnabled(true);
                mError.setText(data.getStringExtra("error"));
            }
        }
    }

    private void setInterfaceEnabled(boolean enabled) {
        mSave.setVisible(enabled);
        mNameField.setEnabled(enabled);
        mDescField.setEnabled(enabled);
        mDefaultField.setEnabled(enabled);
    }

    private class CryptoAdapter extends ArrayAdapter<CryptoImplementation> {
        public CryptoAdapter(Context context) {
            super(context, android.R.layout.simple_spinner_item);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

            List<CryptoImplementation> instances = CryptoFactory.getInstances();
            mDefaultPos = 0;
            for (CryptoImplementation instance : instances) {
                add(instance);
                if (instance.getId() == DEFAULT_CRYPTO_IMPL)
                    mDefaultPos = getPosition(instance);
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = super.getView(position, convertView, parent);
            setViewText(v, position);
            return v;
        }

        @Override
        public View getDropDownView (int position, View convertView, ViewGroup parent) {
            View v = super.getDropDownView(position, convertView, parent);
            setViewText(v, position);
            return v;
        }

        private void setViewText(View v, int position) {
            TextView text = (TextView) v.findViewById(android.R.id.text1);
            text.setText(getItem(position).getName());
        }
    }

    public static class IdentityWaiterFrag extends TaskFragment<Object, String, String> {
        static final String CREATE_NEW = "create_new";
        static final String CRYPTO_IMPL_ID = "crypto_impl_id";
        static final String KEY = "key";
        static final String PUBLIC_NAME = "public_name";
        static final String DESCRIPTION = "description";
        static final String EMAIL_ADDRESS = "email_address";
        static final String SET_DEFAULT = "set_default";

        String currentStatus;
        TextView mStatus;

        public static IdentityWaiterFrag newInstance(
                boolean createNew, int cryptoImplId, String key, String publicName, String description, String emailAddress, boolean setDefault) {
            IdentityWaiterFrag f = new IdentityWaiterFrag();
            Bundle args = new Bundle();
            args.putBoolean(CREATE_NEW, createNew);
            args.putInt(CRYPTO_IMPL_ID, cryptoImplId);
            args.putString(KEY, key);
            args.putString(PUBLIC_NAME, publicName);
            args.putString(DESCRIPTION, description);
            args.putString(EMAIL_ADDRESS, emailAddress);
            args.putBoolean(SET_DEFAULT, setDefault);
            f.setArguments(args);
            return f;
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.dialog_status, container, false);
            mStatus = (TextView) v.findViewById(R.id.status);

            if (currentStatus != null && !currentStatus.isEmpty())
                mStatus.setText(currentStatus);

            return v;
        }

        @Override
        public Object[] getParams() {
            Bundle args = getArguments();
            return new Object[] {
                    Boolean.valueOf(args.getBoolean(CREATE_NEW)),
                    Integer.valueOf(args.getInt(CRYPTO_IMPL_ID)),
                    args.getString(KEY),
                    args.getString(PUBLIC_NAME),
                    args.getString(DESCRIPTION),
                    args.getString(EMAIL_ADDRESS),
                    Boolean.valueOf(args.getBoolean(SET_DEFAULT)),
            };
        }

        @Override
        public void updateProgress(String... values) {
            currentStatus = values[0];
            mStatus.setText(currentStatus);
        }

        @Override
        public void taskFinished(String result) {
            super.taskFinished(result);

            if (getTargetFragment() != null) {
                Intent i = new Intent();
                i.putExtra("result", result);
                getTargetFragment().onActivityResult(
                        getTargetRequestCode(), Activity.RESULT_OK, i);
            }
        }

        @Override
        public void taskCancelled(String error) {
            super.taskCancelled(error);

            if (getTargetFragment() != null) {
                Intent i = new Intent();
                i.putExtra("error", error);
                getTargetFragment().onActivityResult(
                        getTargetRequestCode(), Activity.RESULT_CANCELED, i);
            }
        }
    }

    private class IdentityWaiter extends RobustAsyncTask<Object, String, String> {
        protected String doInBackground(Object... params) {
            StatusListener lsnr = new StatusListener() {
                public void updateStatus(String status) {
                    publishProgress(status);
                }
            };
            try {
                BoteHelper.createOrModifyIdentity(
                        (Boolean) params[0],
                        (Integer) params[1],
                        (String) params[2],
                        (String) params[3],
                        (String) params[4],
                        (String) params[5],
                        (Boolean) params[6],
                        lsnr);
                lsnr.updateStatus("Saving identity");
                I2PBote.getInstance().getIdentities().save();
                return null;
            } catch (Throwable e) {
                cancel(false);
                return e.getMessage();
            }
        }
    }
}