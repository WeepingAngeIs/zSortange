"""Check both game logs only after the hosted Minecraft process has exited."""
import hashlib
import json
import os
from pathlib import Path
import re


PRIOR_LOG_HASH = {
    "synthetic": "1aa887aac59772b2f7a7633f5735e9cff896da00204de9f40c0768a1c7b4e86a",
    "absent": "6d33b11444e1eca1d9f31837d4e9b74d73e54881276aeb7b4102f4c392627c3c",
}
ERROR = re.compile(r"/(?:ERROR|FATAL)\]|Exception in thread |^\s*(?:Caused by: )?[\w.$]+(?:Exception|Error)(?:$|:)")


def read_shutdown(path):
    text = path.read_text(encoding="utf-8", errors="replace")
    stop = re.search(r"(?m)^.*\bStopping!\s*$", text)
    if stop is None:
        raise ValueError(f"{path}: Minecraft never logged its normal stop")
    lines = text[stop.start():].splitlines()
    return {
        "world_save_complete": any("All dimensions are saved" in line for line in lines),
        "shutdown_failures": sum("Shutdown failure!" in line for line in lines),
        "concurrent_modifications": sum("ConcurrentModificationException" in line for line in lines),
        "texture_close_stack_frames": sum("TextureManager.close(" in line for line in lines),
        "shutdown_errors": [line for line in lines if ERROR.search(line)],
    }


def main():
    trial = os.environ["TRIAL"]
    base = Path("build") / ("back-fit-" + trial)
    evidence = base / "evidence"
    previous = Path("prior-fit")
    prior_tail = previous / "client-tail.txt"
    if hashlib.sha256(prior_tail.read_bytes()).hexdigest() != PRIOR_LOG_HASH[trial]:
        raise ValueError("The pre-fix tail differs from the reviewed GitHub artifact")
    prior = read_shutdown(prior_tail)
    stdout = read_shutdown(base / "evidence-logs" / "client.log")
    minecraft = read_shutdown(base / "game" / "logs" / "latest.log")
    code = int((base / "evidence-logs" / "exit-code.txt").read_text())
    marker = evidence / "texture-close.txt"
    texture_result = marker.read_text().strip() if marker.exists() else ""
    result = {
        "trial": trial,
        "baseline_run": f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['BASE_RUN']}",
        "baseline_commit": os.environ["BASE_SHA"],
        "candidate_commit": os.environ["GITHUB_SHA"],
        "baseline_fit": (previous / "PASS.txt").read_text().strip(),
        "candidate_fit": (evidence / "PASS.txt").read_text().strip(),
        "baseline_shutdown": prior,
        "exit_code": code,
        "candidate_console_shutdown": stdout,
        "candidate_game_log_shutdown": minecraft,
        "texture_close_return": texture_result,
    }
    summary = json.dumps(result, indent=2)
    (evidence / "shutdown-comparison.json").write_text(summary + "\n")
    print(summary, flush=True)
    signatures = ("shutdown_failures", "concurrent_modifications", "texture_close_stack_frames")
    expected_before = 1 if trial == "synthetic" else 0
    if not prior["world_save_complete"] or any(prior[key] != expected_before for key in signatures):
        raise SystemExit("The verified pre-fix reproduction was not found")
    if not (result["baseline_fit"] and result["candidate_fit"] and texture_result) or code:
        raise SystemExit("Both fit results, verified texture close return, and exit 0 are required")
    for log in (stdout, minecraft):
        if not log["world_save_complete"] or log["shutdown_errors"] or any(log[key] for key in signatures):
            raise SystemExit("The candidate did not shut down cleanly; see shutdown-comparison.json")


if __name__ == "__main__":
    main()
