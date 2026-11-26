# Releasing to Maven Central

The release is signed and uploaded by the author from his own machine. The signing key stays in his GPG keyring,
gpg-agent asks for its passphrase, and the upload goes through the Central Portal's web page or its publisher API.
Nothing in this repository or in CI holds the key, its passphrase or a Portal token (docs/OPEN-QUESTIONS.md OQ5). CI checks the same
signing setup on every push, with a throwaway key it makes itself (job `published-artifacts`).

Requirements: https://central.sonatype.org/publish/requirements/ and
https://central.sonatype.org/publish/requirements/gpg/ (read 2026-11-23 and 2026-11-25). Bundle format:
https://central.sonatype.org/publish/publish-portal-upload/ (read 2026-11-25).

## Once: the signing key

1. Create the key; `gpg` asks for a passphrase. Central verifies signatures against the **primary** key, so the key
   must have no separate signing subkey. The command makes a primary key that signs and nothing else:

   ```bash
   gpg --quick-generate-key "Your Name <your@address>" rsa4096 sign 2y
   ```

   The name and address become public on the key servers. gpg also writes a revocation certificate to
   `~/.gnupg/openpgp-revocs.d/`.
2. Note the key's fingerprint: the 40 hex digits on the line under `sec`. The steps below use it as `<FINGERPRINT>`.

   ```bash
   gpg --list-secret-keys --keyid-format long
   ```
3. Publish the public key to a key server that Central supports (keyserver.ubuntu.com, keys.openpgp.org,
   pgp.mit.edu):

   ```bash
   gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>
   ```
4. Back up the secret key **outside the repository** (and then off this machine, with the revocation certificate):

   ```bash
   gpg --armor --export-secret-keys <FINGERPRINT> > ~/raa-signing-key-backup.asc && chmod 600 ~/raa-signing-key-backup.asc
   ```

## Each release

The version comes from the command line; `gradle.properties` keeps the next `-SNAPSHOT`, which staging refuses. Each
step stops the release if it fails.

1. Start from a clean, pushed `main` and note the commit that will be released:

   ```bash
   git status --porcelain && git fetch origin && git status -sb && git rev-parse HEAD
   ```

   `git status --porcelain` must print nothing, and `git status -sb` must not show the branch ahead of or behind
   `origin/main`.
2. Stage and sign. gpg-agent asks for the passphrase; in a terminal without a desktop, run `export GPG_TTY=$(tty)`
   first.

   ```bash
   rm -rf build/staging-deploy && ./gradlew publish -Pversion=0.1.0 -Psign -Psigning.gnupg.keyName=<FINGERPRINT>
   ```
3. Check the staged files as an application gets them, from the module metadata and from the POMs only. The check
   requires a good signature of `<FINGERPRINT>` on every file:

   ```bash
   ./gradlew -p consumer-check check -PraaVersion=0.1.0 -Psigned -PsigningKey=<FINGERPRINT> --rerun-tasks
   ```

   ```bash
   ./gradlew -p consumer-check check -PraaVersion=0.1.0 -Psigned -PsigningKey=<FINGERPRINT> -PpomOnly --rerun-tasks
   ```
4. Make the bundle in the Maven repository layout and look at its contents. The `maven-metadata.xml` files and their
   checksums are left out, because the bundle format does not mention them.

   ```bash
   rm -f build/central-bundle.zip && (cd build/staging-deploy && zip -qr ../central-bundle.zip io -x '*maven-metadata.xml*')
   ```

   ```bash
   unzip -l build/central-bundle.zip | tail -1
   ```

   For 0.1.0 `unzip` lists 241 entries: 37 files, each with four checksums and a signature (222), and 19 directories.
5. Upload: on https://central.sonatype.com, open **Publish** and choose **Publish Component**. Give the deployment a
   name, upload `build/central-bundle.zip` and wait for validation. Then press **Publish**. A published version can
   never be changed or removed. Sign in to the Portal account that owns the namespace (**Namespace** shows it as
   verified); another sign-in method can be another account.

   Or through the publisher API (https://central.sonatype.org/publish/publish-portal-api/, read 2026-11-26; used for
   0.1.0) with a user token (**View Account**, **Generate User Token**). Keep the token out of files, the shell history
   and chats, and revoke it afterwards. `USER_MANAGED` stops at `VALIDATED`; publish on the Portal's **Deployments**
   page after checking the deployment:

   ```bash
   read -r -p "token username: " tu; read -r -s -p "token password: " tp; echo; auth="Authorization: Bearer $(printf '%s:%s' "$tu" "$tp" | base64 -w0)"; unset tp
   ```

   ```bash
   id=$(curl -sS --fail-with-body -H "$auth" --form bundle=@build/central-bundle.zip 'https://central.sonatype.com/api/v1/publisher/upload?name=raa-0.1.0&publishingType=USER_MANAGED') && echo "$id"
   ```

   ```bash
   curl -sS --fail-with-body -X POST -H "$auth" "https://central.sonatype.com/api/v1/publisher/status?id=$id" | jq '{deploymentState, purls, errors}'
   ```
6. Tag the commit from step 1 and push the tag:

   ```bash
   git tag -a v0.1.0 -m "reliable-agent-actions 0.1.0" <COMMIT> && git push origin v0.1.0
   ```
7. Create the GitHub release for the tag on the repository's **Releases** page (or `gh release create v0.1.0`).
