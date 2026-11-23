#!/bin/bash
# Runs the consumer benchmark on a dedicated GCE spot VM, for reportable numbers (README.md).
#
# usage: bench/consumers/vm.sh --project PROJECT --results DIR [--arch amd64|arm64]
#            [--zones Z,...] [--max-run DURATION] [APP ...]
#
# --project   the GCP project (or BENCH_VM_PROJECT); never written in the repo
# --results   the migration results to take the Thwart versions from, as run.sh's RESULTS
# --arch      amd64 runs on c4-standard-16, arm64 on c4a-standard-16 (default amd64)
# --zones     zones to try, in order, until one has capacity
# --max-run   GCE deletes the VM after this long whatever happens (default 8h)
# REPS, WARMUP, DURATION and CONCURRENCY pass through to run.sh.
#
# It creates the VM (Ubuntu 24.04, spot, no service account), copies HEAD over as a git bundle
# (commit first: uncommitted changes are not sent) with the results' score.json and diff.patch
# files, runs run.sh there with BENCH_REPORTABLE=yes, copies the output back to
# bench/consumers/results/vm-<UTC time>-<arch>/ with the VM's machine type, zone and CPU added to
# machine.txt, and deletes the VM on every exit, an interrupt included (by the run's label,
# bench-run=<time>-<arch>). Needs gcloud logged in, with compute rights and
# SSH to the VM.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
die() { echo "vm.sh: $*" >&2; exit 2; }

project=${BENCH_VM_PROJECT:-} results='' arch=amd64 maxrun=8h
zones=us-central1-a,us-central1-b,us-central1-c,us-east4-a,us-east4-b,us-east4-c
while [ $# -gt 0 ]; do
  case $1 in
    --project) project=${2:?}; shift ;;
    --results) results=${2:?}; shift ;;
    --arch) arch=${2:?}; shift ;;
    --zones) zones=${2:?}; shift ;;
    --max-run) maxrun=${2:?}; shift ;;
    -h | --help) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) break ;;
  esac
  shift
done
[ -n "$project" ] || die "needs a project: --project PROJECT or BENCH_VM_PROJECT"
[ -n "$results" ] && [ -d "$results" ] || die "needs --results DIR (the migration results)"
case $arch in amd64) machine=c4-standard-16 ;; arm64) machine=c4a-standard-16 ;; *) die "--arch is amd64 or arm64" ;; esac
command -v gcloud > /dev/null || die "gcloud is not installed"
gcloud compute project-info describe --project "$project" --format='value(name)' > /dev/null ||
  die "gcloud cannot reach the project (gcloud auth login?)"

run=$(date -u +%Y%m%d-%H%M%S)
name=consumer-bench-$run-$arch
out=$here/results/vm-$run-$arch
tmp=$(mktemp -d)
mkdir -p "$out"
git -C "$root" bundle create "$tmp/src.bundle" HEAD 2> /dev/null
(cd "$results" && find . \( -name score.json -o -name diff.patch \) -print0 | tar --null -T - -czf "$tmp/results.tgz")

zone='' creating='' label=$run-$arch
# Every exit, an interrupt included: wait for a create in flight, then delete every VM that
# carries this run's label. Further signals can't cut that short.
cleanup() {
  trap '' INT TERM HUP
  [ -z "$creating" ] || wait "$creating" 2> /dev/null || :
  gcloud compute instances list --project "$project" --filter="labels.bench-run=$label" \
      --format='value(name,zone.basename())' 2> /dev/null |
    while read -r n z; do
      gcloud compute instances delete "$n" --zone "$z" --project "$project" --quiet > /dev/null 2>&1 || :
    done
  rm -rf "$tmp"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

g() { gcloud "$@" --project "$project" --quiet; }
gssh() { g compute ssh "$name" --zone "$zone" --strict-host-key-checking=no --ssh-flag=-oConnectTimeout=15 --command "$1"; }

for z in ${zones//,/ }; do
  g compute instances create "$name" --zone "$z" --machine-type "$machine" \
      --provisioning-model=SPOT --instance-termination-action=DELETE --max-run-duration="$maxrun" \
      --image-family "ubuntu-2404-lts-$arch" --image-project ubuntu-os-cloud \
      --boot-disk-size=100GB --boot-disk-type=hyperdisk-balanced \
      --no-service-account --no-scopes --labels "purpose=bench,bench-run=$label" > /dev/null 2> "$tmp/create.err" &
  # In the background, so a signal is handled at once and cleanup can wait for it.
  creating=$!
  rc=0
  wait "$creating" || rc=$?
  creating=''
  if [ "$rc" = 0 ]; then
    zone=$z
    break
  fi
  echo "$z: $(tail -n 1 "$tmp/create.err")" >&2
done
[ -n "$zone" ] || die "no zone had a $machine"
echo "vm.sh: $name in $zone ($machine, spot)"
for i in $(seq 1 30); do gssh true > /dev/null 2>&1 && break; [ "$i" = 30 ] && die "no SSH after 5 minutes"; sleep 10; done

gssh 'sudo systemctl stop unattended-upgrades apt-daily.timer apt-daily-upgrade.timer > /dev/null 2>&1; sudo apt-get update -qq > /dev/null && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker.io git python3 > /dev/null'
g compute scp "$tmp/src.bundle" "$tmp/results.tgz" "$name:" --zone "$zone" --strict-host-key-checking=no > /dev/null
# run.sh runs as root (for Docker) in this user's clone.
gssh 'git clone -q src.bundle thwart && mkdir -p results && tar xzf results.tgz -C results && sudo git config --global --add safe.directory "*"'
# shellcheck disable=SC2016 # expanded on the VM
gssh 'm=http://metadata.google.internal/computeMetadata/v1/instance; h=Metadata-Flavor:Google; echo "machine type: $(curl -sf -H $h $m/machine-type | sed "s|.*/||") (spot) in $(curl -sf -H $h $m/zone | sed "s|.*/||")"' > "$tmp/vm.txt"

# Detached, so a dropped SSH session does not stop it; polled until it writes its exit code.
env=$(printf '%s=%q ' REPS "${REPS:-5}" WARMUP "${WARMUP:-30}" DURATION "${DURATION:-5}" CONCURRENCY "${CONCURRENCY:-1 8 32}")
gssh "sudo setsid nohup env $env RESULTS=\$HOME/results OUT=\$HOME/out BENCH_REPORTABLE=yes bash -c 'thwart/bench/consumers/run.sh $*; echo \$? > \$HOME/rc' > run.log 2>&1 < /dev/null &"
while :; do
  sleep 60 &
  wait $!
  if ! g compute instances describe "$name" --zone "$zone" --format='value(status)' > /dev/null 2>&1; then
    die "the VM is gone (preempted, or past --max-run); nothing was copied back"
  fi
  gssh 'test -f rc' > /dev/null 2>&1 && break
done
gssh 'sudo cp run.log rc out/ && sudo tar czf out.tgz -C out .'
g compute scp "$name:out.tgz" "$tmp/out.tgz" --zone "$zone" --strict-host-key-checking=no > /dev/null
tar xzf "$tmp/out.tgz" -C "$out"
{ cat "$tmp/vm.txt"; cat "$out/machine.txt"; } > "$tmp/machine.txt" && mv "$tmp/machine.txt" "$out/machine.txt"
python3 "$here/lib/consumers.py" report "$out"
echo "vm.sh: report in $out/report.md"
