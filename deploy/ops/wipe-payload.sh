# Runs INSIDE the lubuntu-agent pod via `kubectl exec -i <pod> -- sh`.
# Runbook mode 6: kill the whole user stack, drop browser session state.
# The node watchdog then rebuilds it within ~75s.
# Case patterns are bracket-broken so a pattern never matches its own sh.
for p in $(ls /proc | grep -E '^[0-9]+$'); do
  cmd=$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null)
  case "$cmd" in
    *Xvf[b]*) kill -9 $p 2>/dev/null; echo "killed Xvfb $p";;
    *fluxbo[x]*) kill -9 $p 2>/dev/null; echo "killed fluxbox $p";;
    *soca[t]*9222*) kill -9 $p 2>/dev/null; echo "killed socat $p";;
    *x11vn[c]*) kill -9 $p 2>/dev/null; echo "killed x11vnc $p";;
    *google-chrom[e]*) kill -9 $p 2>/dev/null; echo "killed chrome $p";;
  esac
done
rm -rf /home/agent/.playwright-chrome/Default/Sessions \
       "/home/agent/.playwright-chrome/Default/Session Storage" \
       /home/agent/.playwright-chrome/Default/Last\ Session \
       /home/agent/.playwright-chrome/Default/Last\ Tabs
echo "wipe done $(date -u +%H:%M:%S)"
