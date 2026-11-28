package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A kernel that can support multiple user processes.
 */
public class UserKernel extends ThreadedKernel {
    /**
     * Allocate a new user kernel.
     */
	private static HashMap<String, GlobalFileEntry> globalFiles;
	private static Lock fileTableLock;

	private static LinkedList<Integer> freePages;
	private static Lock memLock;
    public UserKernel() {
	super();
    }

    /**
     * Initialize this kernel. Creates a synchronized console and sets the
     * processor's exception handler.
     */
    public void initialize(String[] args) {
	super.initialize(args);

	globalFiles = new HashMap<String, GlobalFileEntry>();
	fileTableLock = new Lock();

	freePages = new LinkedList<Integer>();
	memLock = new Lock();


	int numPhysPages = Machine.processor().getNumPhysPages();
	for(int i = 0; i < numPhysPages; i++) {
		freePages.add(i);
	}

	console = new SynchConsole(Machine.console());
	
	Machine.processor().setExceptionHandler(new Runnable() {
		public void run() { exceptionHandler(); }
	    });
    }

    /**
     * Test the console device.
     */	
    public void selfTest() {
	super.selfTest();

	System.out.println("Testing the console device. Typed characters");
	System.out.println("will be echoed until q is typed.");

	char c;

	do {
	    c = (char) console.readByte(true);
	    console.writeByte(c);
	}
	while (c != 'q');

	System.out.println("");
    }

    /**
     * Returns the current process.
     *
     * @return	the current process, or <tt>null</tt> if no process is current.
     */
    public static UserProcess currentProcess() {
	if (!(KThread.currentThread() instanceof UThread))
	    return null;
	
	return ((UThread) KThread.currentThread()).process;
    }

    /**
     * The exception handler. This handler is called by the processor whenever
     * a user instruction causes a processor exception.
     *
     * <p>
     * When the exception handler is invoked, interrupts are enabled, and the
     * processor's cause register contains an integer identifying the cause of
     * the exception (see the <tt>exceptionZZZ</tt> constants in the
     * <tt>Processor</tt> class). If the exception involves a bad virtual
     * address (e.g. page fault, TLB miss, read-only, bus error, or address
     * error), the processor's BadVAddr register identifies the virtual address
     * that caused the exception.
     */
    public void exceptionHandler() {
	Lib.assertTrue(KThread.currentThread() instanceof UThread);

	UserProcess process = ((UThread) KThread.currentThread()).process;
	int cause = Machine.processor().readRegister(Processor.regCause);
	process.handleException(cause);
    }

    /**
     * Start running user programs, by creating a process and running a shell
     * program in it. The name of the shell program it must run is returned by
     * <tt>Machine.getShellProgramName()</tt>.
     *
     * @see	nachos.machine.Machine#getShellProgramName
     */
    public void run() {
	super.run();

	UserProcess process = UserProcess.newUserProcess();
	
	String shellProgram = Machine.getShellProgramName();	
	Lib.assertTrue(process.execute(shellProgram, new String[] { }));

	KThread.currentThread().finish();
    }

    /**
     * Terminate this kernel. Never returns.
     */
    public void terminate() {
	super.terminate();
    }

    /** Globally accessible reference to the synchronized console. */
    public static SynchConsole console;

    // dummy variables to make javac smarter
    private static Coff dummy1 = null;

	private static class GlobalFileEntry{
		int openCount = 0;
		boolean pendingUnlink = false;
	}

	public static OpenFile openFile(String filename, boolean create) {
		fileTableLock.acquire();

		try{
			GlobalFileEntry entry = globalFiles.get(filename);

			if(entry != null && entry.pendingUnlink){
				return null;
			}

			OpenFile file = ThreadedKernel.fileSystem.open(filename, create);
			if(file == null){
				return null;
			}

			if(entry == null){
				entry = new GlobalFileEntry();
				globalFiles.put(filename, entry);
			}
			entry.openCount++;
			return file;
		} finally{
			fileTableLock.release();
		}
	}

	public static void closeFile(OpenFile file) {
		if(file == null) return;

		String filename = file.getName();
		file.close();

		if(filename == null) return;
		fileTableLock.acquire();

		try{
			GlobalFileEntry entry = globalFiles.get(filename);
			if(entry != null){
				entry.openCount--;
				if(entry.openCount <= 0){
					globalFiles.remove(filename);
					if(entry.pendingUnlink){
						ThreadedKernel.fileSystem.remove(filename);
					}
				}
			}
		} finally{
			fileTableLock.release();
		}
	}

	public static boolean unlinkFile(String filename) {
		fileTableLock.acquire();
		try {
			GlobalFileEntry entry = globalFiles.get(filename);
			if(entry != null){
				if(entry.pendingUnlink){
					return false;
				}
				entry.pendingUnlink = true;
				return true;
			}
			return ThreadedKernel.fileSystem.remove(filename);
		} finally {
			fileTableLock.release();
		}
	}

	public static int allocatePages(){
		memLock.acquire();

		try{
			if(freePages.isEmpty()){
				return -1;
			}
			return freePages.removeFirst();
		} finally{
			memLock.release();
		}
	}


	public static void freePages(int ppn){
		if (ppn < 0 || ppn >= Machine.processor().getNumPhysPages()){
			return;
		}
		memLock.acquire();
		try{
			freePages.add(ppn);
		} finally {
			memLock.release();
		}
	}
}
