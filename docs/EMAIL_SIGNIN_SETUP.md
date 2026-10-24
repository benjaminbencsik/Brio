# Email sign-in and cross-device library

Brio uses six-digit email codes and Supabase Auth. No Firebase SDK, Google OAuth,
Google Play services, or server under your control is needed. The same flow works
with a remote on Android TV. Credentials are encrypted locally by Android Keystore.

## Owner setup

1. Create a Supabase project. In **Authentication → Sign In / Providers → Email**,
   keep email OTP enabled. In **Authentication → Email Templates → Magic Link**,
   change the template body to include `{{ .Token }}` rather than only a link,
   for example `<p>Your Brio code: {{ .Token }}</p>`.
2. Run [`cloud/supabase.sql`](../cloud/supabase.sql) in the project's SQL editor.
   Row Level Security confines each library to its authenticated account.
3. In the Supabase dashboard's **Connect** dialog or **Settings → API Keys**,
   copy the project URL and **publishable** `sb_publishable_...` key into
   `app/src/main/assets/cloud_config.json`. Do not use a secret/service-role key.
   The publishable key is designed to be bundled in mobile apps; RLS protects
   each user's records.
4. Commit the config and build a new APK. In Brio, open **Settings → Account**,
   enter your email, then type the code sent to that address. Repeat with the
   same email on a second device. Each device keeps its own provider settings;
   only watchlists, progress, episode markers, and series resets sync.

The empty config in the repo keeps sign-in disabled until step 3. Supabase's
built-in mail service is limited to 2 messages per hour per project and is for
testing. Configure custom SMTP before inviting users publicly. Codes expire;
send a new code if one is rejected. OAuth client certificates and SHA values
are unnecessary for this email flow.

The previous Firebase cloud data is not migrated automatically. Local guest
watchlists and progress import into the email account the first time it is
connected on that device. An account previously signed into Firebase needs a
separate export before removing the old app if its library exists only there.

## Verify

- Sign in on a phone and Android TV; update the watchlist on one device, then
  reopen or tap **Sync now** on the other.
- Pause a video and confirm Continue Watching appears on the other device.
- Sign out and confirm the guest library is separate.
- Test the SQL policies with two Supabase users: neither can read or write the
  other's `library_entries` rows. Never disable RLS for troubleshooting.

References: https://supabase.com/docs/guides/auth/auth-email-passwordless
and https://supabase.com/docs/guides/api/securing-your-api
