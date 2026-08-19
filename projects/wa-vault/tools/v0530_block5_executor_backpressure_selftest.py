#!/usr/bin/env python3
from pathlib import Path
import subprocess, tempfile, textwrap, shutil
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/com/fer/wavault/VaultExecutors.java'
with tempfile.TemporaryDirectory(prefix='wa_vault_executor_test_') as td:
    t=Path(td)
    pkg=t/'com/fer/wavault'; pkg.mkdir(parents=True)
    shutil.copy2(SRC,pkg/'VaultExecutors.java')
    (pkg/'VaultExecutorsSelfTest.java').write_text(textwrap.dedent('''
        package com.fer.wavault;
        import java.util.concurrent.*;
        import java.util.concurrent.atomic.*;
        public final class VaultExecutorsSelfTest {
          public static void main(String[] args) throws Exception {
            ThreadPoolExecutor ex=VaultExecutors.bounded(1,2,"bounded-selftest",Thread.NORM_PRIORITY);
            CountDownLatch hold=new CountDownLatch(1);
            AtomicInteger ran=new AtomicInteger();
            ex.execute(()->{try{hold.await();}catch(InterruptedException e){Thread.currentThread().interrupt();} ran.incrementAndGet();});
            ex.execute(ran::incrementAndGet);
            ex.execute(ran::incrementAndGet);
            String caller=Thread.currentThread().getName();
            AtomicReference<String> rejectedThread=new AtomicReference<>("");
            boolean rejected=false;
            try { ex.execute(()->{rejectedThread.set(Thread.currentThread().getName()); ran.incrementAndGet();}); }
            catch(RejectedExecutionException expected){ rejected=true; }
            if(!rejected) throw new AssertionError("queue saturation did not reject");
            if(caller.equals(rejectedThread.get())) throw new AssertionError("rejected work ran on caller thread");
            hold.countDown();
            ex.shutdown();
            if(!ex.awaitTermination(5,TimeUnit.SECONDS)) throw new AssertionError("executor did not terminate");
            if(ran.get()!=3) throw new AssertionError("unexpected executed task count="+ran.get());
            System.out.println("VAULT_EXECUTORS_BACKPRESSURE_PASS executed="+ran.get()+" rejected=1 callerRuns=0");
          }
        }
    '''),encoding='utf-8')
    subprocess.run(['javac','-d',str(t),str(pkg/'VaultExecutors.java'),str(pkg/'VaultExecutorsSelfTest.java')],check=True)
    out=subprocess.run(['java','-cp',str(t),'com.fer.wavault.VaultExecutorsSelfTest'],check=True,text=True,capture_output=True)
    print(out.stdout.strip())
