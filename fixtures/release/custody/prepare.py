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
    assert hashlib.sha256(Path(os.environ["COMBINED_STORAGE_JAR"]).read_bytes()).hexdigest()==os.environ["STORAGE_SHA"]

if __name__=="__main__":
    main()
