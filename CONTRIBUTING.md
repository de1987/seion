# Contributing to Seion

Thanks for your interest in Seion. Bug reports, ideas, translations and code are all welcome, whether it's your
first open-source contribution or your hundredth.

## Reporting a bug or suggesting a feature

Open an issue on GitHub. For bugs, these details help the most:

- your phone model and Android version;
- the Seion version (Settings shows it);
- the steps that lead to the problem, and what you expected instead;
- the audio format involved, if it is about playback (for example a 24-bit FLAC or an AAC file from a certain app).

For a security problem, please don't open a public issue. Report it privately with "Report a vulnerability" on the
repository's Security tab.

## Contributing code

1. Fork the repository and create a branch for your change.
2. Set up the build as described in [DEVELOPMENT.md](DEVELOPMENT.md): JDK 17 and the Android SDK are all you need.
3. Make your change, with a test where it makes sense. Bug fixes should come with a test that fails without the fix.
4. Run the full check before you open a pull request:

   ```sh
   make format   # fixes Kotlin style
   make check    # build, unit tests, ktlint and Android lint
   ```

5. Open a pull request that explains what changed and why. Screenshots or a short clip help for anything visual.

Small, focused pull requests are easier to review and get merged sooner. For a larger change, such as a new screen
or a change to the audio engine, open an issue first so we can agree on the approach before you put in the work.

## Translations

Seion is available in English and Brazilian Portuguese. To add a language, copy
`app/src/main/res/values/strings.xml` into a new `values-<language>` folder and translate the strings that aren't
marked `translatable="false"`. The build treats missing translations as errors, so `make check` tells you if
anything is left.

## A few ground rules

- Keep Seion private by design: no analytics, no accounts, and no new network requests without a setting to turn
  them off.
- Use only material you have the right to share. Screenshots and test files should use fictional or freely licensed
  music and artwork.
- Be kind and constructive in issues and reviews.

## License

Seion is licensed under the GNU General Public License, version 3 or later. By submitting a contribution, you agree
that it is licensed under the same terms.
