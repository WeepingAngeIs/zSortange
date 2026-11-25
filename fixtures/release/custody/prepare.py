"""Extend only the pinned upstream observer in this disposable hosted checkout."""
import hashlib
import os
from pathlib import Path

def main():
    assert os.environ.get("RUNNER_ENVIRONMENT")=="github-hosted"
    root=Path.cwd()
    own=Path(__file__).resolve().parent
    upstream=root/".github/native249"
    # The verified upstream probe already builds exactly one original helper.
    # Add the second-owner observation after its ordinary, real first admission.
    script=upstream/"combined_prepare.py"
    text=script.read_text()
    marker='shutil.copy2(root/".github/native249/CombinedStorageProof.java", src)'
    assert text.count(marker)==1
    text=text.replace(marker,marker+'\nshutil.copy2(root/".github/native249/IndependentLoss.java", src)')
    (upstream/"IndependentLoss.java").write_bytes((own/"IndependentLoss.java").read_bytes())
    script.write_text(text)
    full=upstream/"FullPackSession.java"
    text=full.read_text()
    marker='        snapshot(player,"held");'
    assert text.count(marker)==1
    full.write_text(text.replace(marker,marker+'\n        IndependentLoss.run(player);\n        if (IndependentLoss.complete) return;'))
    # Comparison copies written by the test observer are not live world stores.
    # Keep them in the observer's own output directory without changing the mod's scanner.
    observer=root/".github/detention/PlayerOnlySmoke.java"
    text=observer.read_text()
    marker='return CertifiedJailSmoke.world.resolve("center-"'
    assert text.count(marker)==1
    observer.write_text(text.replace(marker,'return results.resolve("center-"'))
    wallet=root/".github/packed-wallet/PackedWalletProof.java"
    text=wallet.read_text()
    marker='CertifiedJailSmoke.world.resolve("packed-wallet-expected.nbt")'
    assert text.count(marker)==2
    wallet.write_text(text.replace(marker,'PlayerOnlySmoke.results.resolve("packed-wallet-expected.nbt")'))
    # Retain the same kernel memory limits and independent runner supervisor as the
    # proven full-pack rehearsal. Substitute only the dedicated observation driver.
    scope=(upstream/"game-scope.sh").read_text()
    marker='driver=.github/native249/combined_run.py'
    assert scope.count(marker)==1
    (upstream/"independent-scope.sh").write_text(scope.replace(marker,'driver=storage-check/fixtures/release/custody/play.py'))
    assert hashlib.sha256(Path(os.environ["COMBINED_STORAGE_JAR"]).read_bytes()).hexdigest()==os.environ["STORAGE_SHA"]

if __name__=="__main__":
    main()
