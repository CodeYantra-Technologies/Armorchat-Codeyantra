# ArmorChat licensing and commercial-readiness audit

Date: 25 November 2026. Scope: current working tree, available Git history,
three Rust lockfiles, two npm lockfiles, Gradle manifests and local POM cache,
packaged native library locations, existing notices, and source license markers.
This is an engineering evidence review, not a legal ownership certification,
security audit, or clearance of a final release artifact.

## Decision

The repository now documents AGPL-3.0-only as the public project license and a
separately executed commercial agreement as an alternative for rights the
licensor controls. **No agreement has been signed and no customer commercial
license has been activated by these edits.** Do not represent this scan as
complete commercial-release clearance. Third-party rights remain independent.

## Evidence and results

The scan is recorded in [license-inventory.json](license-inventory.json).
Regenerate local-cache evidence with `python3 scripts/license_inventory.py`;
use `--online` to supplement missing npm licenses from exact-version registry
metadata. An offline regeneration may have less npm coverage. The inventory
includes the Git HEAD; uncommitted source edits are also in scope for text review
but are not represented by that commit hash. Cache files are only evidence for
the versions and hashes recorded, not proof of what a release contains.

| Area | Evidence | Result / limit |
| --- | --- | --- |
| Git provenance | All locally available authors and committers | One identity: Neeraj Mishra. No inference of corporate assignment, employer consent, originality, or complete external history. |
| Rust: mls-rs bridge | 142 third-party lockfile entries | Archive SHA-256 verified against lockfile; license metadata read from archive Cargo.toml. No MPL-only entry identified. |
| Rust: OpenMLS bridge | 221 third-party lockfile entries | Same archive verification; four MPL-2.0 entries identified. |
| Rust: vodozemac bridge | 107 third-party lockfile entries | Same archive verification; no MPL-only entry identified. |
| npm: relay and Deal backend | 191 dependency installation-path entries across two lockfiles | License metadata found for all entries using cache/installed manifests and exact-version npm registry records. Registry metadata is not verified license text. |
| Gradle cache | 81 cached POMs | Ten contain no explicit license name. Cache includes artifacts unrelated to this release and is not the resolved app dependency graph. |
| Native binaries | Ten files in app/native jniLibs directories, hashes recorded | Seven are under app/src/main/jniLibs, including all three bridge libraries for arm64-v8a. Source-to-binary reproducibility not established. |
| Assets / names | Existing provenance notes, first-party brand statements | Originality and assignments require retained design records; no trademark grant implied. |

Counts are per lockfile or installation path, not unique package totals. Rust
lockfiles can include inactive, optional, target-specific, and test packages.
No GPL/AGPL-only or SSPL declaration was found in the scanned third-party Rust
and npm metadata. The Rust package `r-efi` offers MIT OR Apache-2.0 OR
LGPL-2.1-or-later; its LGPL alternative does not force that choice. This is not
a claim that all transitive Android code is free of copyleft or proprietary terms.

## Findings and required release work

1. **OpenMLS is present in the packaging directory.** Both arm64-v8a and x86_64
   OpenMLS binaries exist under app/src/main/jniLibs; arm64-v8a is the configured
   app ABI. Its lockfile contains `hpke-rs`, `hpke-rs-crypto`,
   `hpke-rs-rust-crypto`, and `hpke-rs-libcrux` 0.7.0 under MPL-2.0. Lockfile
   presence alone does not prove all four were compiled. Confirm the actual
   native dependency graph and APK contents. If shipping MPL-covered code,
   preserve its notices and provide the required source-access information and
   source for the covered files/modifications. Alternatively, deliberately
   remove unused experimental libraries from the release and verify that APK.
   This audit does not delete binaries or change cryptographic behavior.
2. **First-party Rust metadata was stale.** All three bridge Cargo.toml files
   still said Apache-2.0 after the initial root license change. They now declare
   AGPL-3.0-only and point to the separate commercial option. Third-party crate
   metadata and earlier Apache grants are unchanged.
3. **Android release graph remains unverified.** Resolve the exact release
   runtimeClasspath, inspect its AAR/JAR notices and all transitive components,
   and generate a release SBOM. Cached POMs include Android SDK terms,
   test-scope EPL, and a GPL-with-classpath-exception/MIT alternative; do not
   mistake those cache findings for release dependencies. Resolve the ten
   missing POM declarations from actual artifact/upstream license evidence
   where relevant. Gradle currently excludes META-INF/AL2.0 and LGPL2.1;
   packaged duplicate-resource exclusion does not remove notice obligations.
4. **npm evidence is metadata-level.** The relay has no installed node_modules
   in this workspace. Verify each distributed package's license text and
   notices, including bundled code in sql.js, and create deployment-specific
   notices. `limiter` 1.1.5 uses a legacy licenses array declaring MIT; the
   inventory preserves that evidence rather than treating it as missing.
5. **Rights chain is not proven by Git.** Establish the actual legal person
   operating CodeYantra Technologies, Neeraj Mishra's authority, and any needed
   assignment or license from the individual author to that person. Obtain
   signed contributor agreements for outside code included under proprietary
   terms. Repository authorship and a Founder title alone are insufficient.
6. **Previously granted rights survive.** Existing Apache-2.0 copies and public
   AGPL grants are not revoked. Recipients may keep using those copies under
   their applicable terms. Public source cannot become a trade secret merely
   through a new commercial document.
7. **Execution details are incomplete.** Complete the actual licensor identity,
   street address/PIN, customer, covered revision, delivery hashes, fees, term,
   permissions, and notices in the commercial order. Both parties must sign.
   India / Ludhiana is the drafting assumption based on the owner's location;
   legal form, jurisdiction, tax/stamp requirements, and enforceability need
   local legal review before execution.

## Changes applied

- Root and Android public license texts remain the official AGPLv3 text.
- Commercial agreement now includes an explicit conditional proprietary grant,
  scope limits, third-party carve-outs, fee/order fields, liability, termination,
  governing law, and blank human signature blocks.
- Contributor agreement grants explicit commercial sublicensing permission
  only for listed contributions after execution; it does not assign copyright.
- First-party native manifests now match the public license policy.
- Dependency notices now flag native/MPL packaging and direct users to this
  evidence report. Third-party licenses were not replaced with commercial terms.

## Primary references

- [GNU license FAQ: copyright-holder authority and parallel licensing](https://www.gnu.org/licenses/gpl-faq.html#ReleaseUnderGPLAndNF).
- [GNU AGPLv3, including section 13](https://www.gnu.org/licenses/agpl-3.0.html).
- [Mozilla MPL FAQ: executable distribution and covered source](https://www.mozilla.org/en-US/MPL/2.0/FAQ/).
- [Information Technology Act, 2000: electronic execution and contracts](https://www.indiacode.nic.in/bitstream/123456789/13116/1/it_act_2000_updated.pdf).

Primary legal texts explain the rules; this scan does not determine their
application to a signed transaction or certify an execution method.
