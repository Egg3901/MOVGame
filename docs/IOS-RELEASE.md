# iOS internal TestFlight

Web and native clients are maintained in the same repository. Build native iOS
from `apps/native` on `main`. Bundle ID: `com.lakesidegames.electioneer`.

1. Merge the reviewed change after the web and unsigned native checks pass.
2. Select the manual **iOS TestFlight** workflow on `main` and supply its
   full commit as `reviewed_sha`. The workflow rejects any other revision.
3. The workflow archives with Xcode 26.6, exports an internal-only App Store
   IPA, and uploads it directly to App Store Connect. It does not publish
   signing files, IPAs, or archives as GitHub artifacts or releases.
4. Wait for Apple processing to finish, attach the build to an internal test
   group, then install and test on a physical device. A successful upload is
   not proof of app launch, save recovery, or gameplay readiness.

Repository Actions secrets: `APPLE_TEAM_ID`, `IOS_CERTIFICATE` (base64 p12),
`IOS_CERTIFICATE_PASSWORD`, `IOS_MOBILE_PROVISION` (base64 App Store profile),
`APP_STORE_CONNECT_PRIVATE_KEY` (p8 text), `APP_STORE_CONNECT_KEY_IDENTIFIER`,
and `APP_STORE_CONNECT_ISSUER_ID`. Secrets are never checked into the repo.
The signing helper validates profile identity, expiry, distribution type,
and the imported private key before configuring the app target.

Build numbers are 1000 plus the release workflow run number. Keep this
sequence when changing workflows. Only manual default-branch runs can sign;
PR builds compile without credentials. Concurrent uploads are serialized.
