## Summary

<!-- What changed and why. Link the agreed checkpoint. -->

## Validation

<!-- Commands actually run and their results. Keep emulator/device evidence separate from compilation. -->

- [ ] `./gradlew spotlessCheck lint testDebugUnitTest assembleDebug`
- [ ] Emulator or device checks (if UI, sign-in, Custom Tabs or navigation changed)

## Build -> Translate -> Review

<!-- Stage agent IDs, launch/runtime settings and the Review verdict (Approved/Denied). -->

## Public repository checklist

- [ ] No credentials, tokens, keystores, signing config or `.env` files
- [ ] No real server hostnames, IP addresses or infrastructure details (use `example.com`)
- [ ] No real feed content, personal data or signed-in screenshots
- [ ] New dependencies and GitHub Actions are pinned and justified
