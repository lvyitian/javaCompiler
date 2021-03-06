/*
 *   JavaNativeCompiler - A Java to native compiler.
 *   Copyright (C) 2006  Marco Trudel <mtrudel@gmx.ch>
 *
 *   This program is free software; you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation; either version 2 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program; if not, write to the Free Software
 *   Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package ch.mtSystems.jnc.model;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.swt.widgets.Display;

import ch.mtSystems.jnc.model.utilities.ClassUtilities;
import ch.mtSystems.jnc.model.utilities.FileUtilities;
import ch.mtSystems.jnc.model.utilities.SettingsMemory;


public class NativeCompiler
{
	private ICompilationProgressLogger logger;
	private JNCProject project;
	private File outDir;
	
	private File compilerPath;
	private String javaLibPath;
	private boolean guiFilesAdded;
	private boolean suppressCommandLogging = false;


	public NativeCompiler(ICompilationProgressLogger logger, JNCProject project)
	{
		this.logger = logger;
		this.project = project;
		javaLibPath = project.getJavaLibPath();
	}

	public boolean compile() throws Exception
	{
		try
		{
			guiFilesAdded = false;

			if(project.getCompileWindows() && !compile("win")) { beep(true); return false; }
			if(project.getCompileLinux() && !compile("lin")) { beep(true); return false; }

			if(guiFilesAdded)
			{
				logger.log("\n-> You seem to compile an AWT/Swing application. AWT/Swing requires some", false);
				logger.log("libraries at runtime which have been copied into the \"lib\" directory.", false);
				logger.log("Your application might need all, a couple or even none of them. Feel", false);
				logger.log("free to delete the ones which aren't required.", false);

				if(project.getCompileWindows() && project.getCompileLinux() &&
					project.getLinuxFile().getParentFile().equals(project.getWindowsFile().getParentFile()))
				{
					logger.log("-> Please note that, since you compiled the Windows and Linux binary", false);
					logger.log("into the same folder, the required files are now mixed in the same \"lib\"", false);
					logger.log("directory.", false);
				}
			}

			beep(false);
			return true;
		} catch(Exception ex)
		{
			beep(true);
			throw ex;
		}
	}

	private boolean compile(String os) throws Exception
	{
		String arch = (os.equals("win")) ? "Windows" : "Linux";
		File f = (os.equals("win")) ? project.getWindowsFile() : project.getLinuxFile();
		logger.log("creating \"" + f.getName() + "\" for " + arch, false);

		String sCompilerPath = (os.equals("win")) ?
				SettingsMemory.getSettingsMemory().getWindowsCompilerPath() :
				SettingsMemory.getSettingsMemory().getLinuxCompilerPath();
		if(sCompilerPath == null)
		{
			logger.log("No " + arch + " compiler has been set!", true);
			logger.log("You can specify the compiler paths in the settings...", true);
			return false;
		}
		compilerPath = new File(sCompilerPath);

		outDir = FileUtilities.createTempDir("JNCTemp", ".out");
		try
		{
			adaptJavaLibPath(os);
			if(!compileJars(os)) return false;
			if(!finalCompile(os)) return false;
			copyGuiData(os);
		} finally
		{
			FileUtilities.deleteDirRecursively(outDir);
		}

		return true;
	}
	
	private void adaptJavaLibPath(String os)
	{
		if(project.getExcludeGui()) return;

		if(javaLibPath == null)
		{
			javaLibPath = "lib"; 
		} else
		{
			String sep = (os.equals("win")) ? ";" : ":";
			javaLibPath = "lib" + sep + javaLibPath;
		}
	}

	private void copyGuiData(String os) throws Exception
	{
		if(project.getExcludeGui()) return;

		List<File> dirList = new LinkedList<File>();
		dirList.add(new File("libs/" + os + "/gui/lib"));
		dirList.add(os.equals("win") ?
			new File(project.getWindowsFile().getParentFile(), "lib") :
			new File(project.getLinuxFile().getParentFile(), "lib"));

		while(!dirList.isEmpty())
		{
			File curSrcDir = dirList.remove(0);
			File curOutDir = dirList.remove(0);

			if(!curOutDir.exists() && !curOutDir.mkdirs()) throw new Exception("Unable to create \"" + curOutDir + "\"");
			for(File fSrc : curSrcDir.listFiles())
			{
				File fOut = new File(curOutDir, fSrc.getName());

				if(fSrc.isDirectory())
				{
					dirList.add(fSrc);
					dirList.add(fOut);
				} else
				{
					if(fOut.exists()) continue;
					FileUtilities.copyFile(fSrc, fOut);
					guiFilesAdded = true;
				}
			}
		}
	}

	private boolean compileJars(String os) throws Exception
	{
		File[] fa = project.getJars();
		for(File f : fa)
		{
			// test if cached
			String objectType = project.getCompileCompleteJar(f) ? ".o" : ".a";
			String fileName = f.getName();
			fileName = fileName.substring(0, fileName.length()-4) + "-" + os + ".jar" + objectType;
			
			File cachedJar = new File(f.getParentFile(), fileName);
			if(cachedJar.exists()) continue;

			File fTarget = (project.getDontCacheJars()) ? new File(outDir, fileName) : cachedJar;
			if(project.getCompileCompleteJar(f))
			{
				if(!compileSource(f, fTarget, os)) return false;
			} else
			{
				if(!compileJarToArchive(f, fTarget, os)) return false;
			}
		}

		return true;
	}

	private boolean compileSource(File sourceFile, File objectFile, String os) throws Exception
	{
		LinkedList<String> alCmd = new LinkedList<String>();
		alCmd.add((new File(compilerPath, "bin/gcj")).toString());
		if(!project.getUseCni()) alCmd.add("-fjni");
		if(!project.getDisableOptimisation()) alCmd.add("-O2");
		alCmd.add("-Ilibs/" + os + "/gui/gui.jar");
		alCmd.add("-c"); alCmd.add(sourceFile.toString());
		alCmd.add("-o"); alCmd.add(objectFile.toString());

		for(String flag : project.getGcjFlags())
		{
			if(!project.getFlagMainCompilationOnly(flag)) alCmd.add(flag);
		}

		File[] faJars = project.getJars();
		for(File f : faJars)
		{
			// if source is a jar, do not add it to the classpath
			if(!f.equals(sourceFile)) alCmd.add("-I" + f);
		}

		String[] saCmd = alCmd.toArray(new String[0]);
		return runCmd(saCmd, "processing " + sourceFile.getName(), true);
	}
	
	private boolean compilePropertiesFile(File propertiesFile, String propertiesName,
			File objectFile, String os) throws Exception
	{
		LinkedList<String> alCmd = new LinkedList<String>();
		alCmd.add((new File(compilerPath, "bin/gcj")).toString());
		alCmd.add("--resource");
		alCmd.add(propertiesName);
		alCmd.add("-c"); alCmd.add(propertiesFile.toString());
		alCmd.add("-o"); alCmd.add(objectFile.toString());

		for(String flag : project.getGcjFlags())
		{
			if(!project.getFlagMainCompilationOnly(flag)) alCmd.add(flag);
		}

		String[] saCmd = alCmd.toArray(new String[0]);
		return runCmd(saCmd, "processing " + propertiesFile.getName(), true);
	}
	
	private boolean compileJarToArchive(File jarFile, File archiveFile, String os) throws Exception
	{
		logger.log("- processing " + jarFile.getName(), false);
		File tmpDir = FileUtilities.createTempDir("JNCTemp", ".out");
		ZipFile zipFile = new ZipFile(jarFile);
		suppressCommandLogging = true;

		// A temporary archive file is used since the compilation might fail.
		// Otherwise the user might get a broken archive if he caches the jars...
		File tmpArchiveFile = new File(tmpDir, "archive.a");
		
		String[] saCmd = new String[4];
		saCmd[0] = (new File(compilerPath, "bin/ar")).toString();
		saCmd[1] = "qsc";
		saCmd[2] = tmpArchiveFile.toString();

		try
		{

			for(Enumeration e = zipFile.entries(); e.hasMoreElements(); )
			{
				ZipEntry zipEntry = (ZipEntry)e.nextElement();
				if(zipEntry.isDirectory()) continue;

				String zipEntryName = zipEntry.getName(); // something like org/eclipse/swt/Foo.class
				if(!zipEntryName.endsWith(".class") && !zipEntryName.endsWith(".properties")) continue;

				File tmpFile = new File(zipEntryName); 
				File sourceFile = new File(tmpDir, tmpFile.getName()); // only use Foo.class
				File objectFile = new File(tmpDir, zipEntryName.replaceAll("\\/", "_") + ".o");

				InputStream inputStream = zipFile.getInputStream(zipEntry);
				FileOutputStream outputStream = new FileOutputStream(sourceFile);
				byte[] tmp = new byte[10 * 1024]; // 10kb

				while(true)
				{
					int len = inputStream.read(tmp);
					if(len < 0) break;
					outputStream.write(tmp, 0, len);
				}

				outputStream.flush();
				outputStream.close();
				inputStream.close();

				if(zipEntryName.endsWith(".properties"))
				{
					String propName = zipEntryName.replaceAll("\\/", ".");
					if(!compilePropertiesFile(sourceFile, propName, objectFile, os)) return false;
				} else
				{
					if(!compileSource(sourceFile, objectFile, os)) return false;
				}

				saCmd[3] = objectFile.toString();
				if(!runCmd(saCmd, "creating archive " + archiveFile.getName(), true)) return false;

				sourceFile.delete();
				objectFile.delete();
			}

			if(tmpArchiveFile.exists()) FileUtilities.copyFile(tmpArchiveFile, archiveFile);
			else                        logger.log("Warning: Nothing imported, JAR unused!", true);

			return true;
		} finally
		{
			suppressCommandLogging = false;
			FileUtilities.deleteDirRecursively(tmpDir);
			zipFile.close();
		}
	}

	private boolean finalCompile(String os) throws Exception
	{
		File outFile = (os.equals("win")) ? project.getWindowsFile() : project.getLinuxFile();
		if(!outFile.getParentFile().exists() && !outFile.getParentFile().mkdirs())
		{
			throw new Exception("Creating the directory \"" + outFile.getParentFile() + "\" failed!");
		}

		LinkedList<String> alCmd = new LinkedList<String>();
		alCmd.add((new File(compilerPath, "bin/gcj")).toString());

		// Java settings
		alCmd.add("--main=" + project.getMainClass());
		if(!project.getUseCni()) alCmd.add("-fjni");
		if(javaLibPath != null) alCmd.add("-Djava.library.path=" + javaLibPath);

		if(!project.getExcludeGui())
		{
			alCmd.add("-Dsun.java2d.fontpath=");
			alCmd.add("-Djava.home=.");

			if(os.equals("win"))
			{
				alCmd.add("-Djava.awt.graphicsenv=sun.awt.Win32GraphicsEnvironment");
				alCmd.add("-Dawt.toolkit=sun.awt.windows.WToolkit");
				alCmd.add("-Dsun.io.unicode.encoding=UnicodeLittle");
			} else
			{
				alCmd.add("-Djava.awt.graphicsenv=sun.awt.X11GraphicsEnvironment");
			}
		}

		alCmd.add("-Llibs/" + os );
		alCmd.add("-Ilibs/" + os + "/gui/gui.jar");

		// Executable settings
		alCmd.add("-o" + outFile);
		if(os.equals("win"))
		{
			if(!addIcon()) return false;
			if(project.getHideConsole()) alCmd.add("-mwindows");
		}
		if(!project.getOmitStripping()) alCmd.add("-s");
		if(!project.getDisableOptimisation()) alCmd.add("-O2");

		// Advanced settings
		for(String flag : project.getGcjFlags()) alCmd.add(flag);
		if(project.getExcludeGui()) alCmd.add("-ljncNoGui");
		if(project.getExcludeJce()) alCmd.add("-ljncNoJce");
		if(project.getAddGnuRegex()) alCmd.add("-ljncRegex");

		// License
		if(os.equals("win"))
		{
			String license = SettingsMemory.getSettingsMemory().getLicense(); 
			alCmd.add((license != null) ? "-Djnc.license=" + license : "-ljncTrial");
		}

		HashSet<File> hsClasspath = new HashSet<File>(); 
		File fInputList = File.createTempFile("SourceList", ".list", outDir);
		FileWriter fw = new FileWriter(fInputList);
		
		// add all configured files and all files in the configured dirs
		HashSet<File> hsFiles = getSourceFiles();
		for(File f : hsFiles)
		{
			File baseDir;
			String classPackage = ClassUtilities.getPackage(f);
			if(classPackage != null)
			{
				// f.toString() -> Windows: foo\bar\FooBar.java, Linux: foo/bar/FooBar.java
				int endIndex = Math.max(f.toString().indexOf(classPackage.replaceAll("\\.", "\\\\")),
						f.toString().indexOf(classPackage.replaceAll("\\.", "/")));
				baseDir = (endIndex > -1) ?
						new File(f.toString().substring(0, endIndex-1)) :
						f.getParentFile();
			} else
			{
				baseDir = f.getParentFile();
			}
			if(baseDir != null && hsClasspath.add(baseDir)) alCmd.add("-I" + baseDir);

			fw.write("\"" + f.toString().replaceAll("\\\\", "/") + "\"\n");
		}
			
		// add all compiled cached jars
		File[] faJars = project.getJars();
		for(File f : faJars)
		{
			alCmd.add("-I" + f.toString());

			String objectType = project.getCompileCompleteJar(f) ? ".o" : ".a";
			String fileName = f.getName();
			fileName = fileName.substring(0, fileName.length()-4) + "-" + os + ".jar" + objectType;
			
			File cachedJar = new File(f.getParentFile(), fileName);
			if(cachedJar.exists())
			{
				fw.write("\"" + cachedJar.toString().replaceAll("\\\\", "/") + "\"\n");
			}
		}

		// add all temporary objects and archives
		File[] tmpFiles = outDir.listFiles();
		for(File f : tmpFiles)
		{
			if(f.getName().endsWith(".o") || f.getName().endsWith(".a"))
			{
				fw.write("\"" + f.toString().replaceAll("\\\\", "/") + "\"\n");
			}
		}

		fw.flush();
		fw.close();
		alCmd.add("@" + fInputList.toString());

		// compile
		String[] saCmd = alCmd.toArray(new String[0]);
		if(!runCmd(saCmd, "main compilation step", true)) return false;

		if(!project.getOmitPacking())
		{
			String[] saCmdUpx =
				{
					(new File("upx")).getAbsolutePath(),
					"--best",
					"-q",
					outFile.toString()
				};
			if(!runCmd(saCmdUpx, "packing binary", false)) return false;
		}

		return true;
	}

	private HashSet<File> getSourceFiles()
	{
		HashSet<File> hs = new HashSet<File>();

		// files
		File[] faFiles = project.getFiles();
		for(File f : faFiles) hs.add(f);

		// directories
		LinkedList<File> llUnreadDirs = new LinkedList<File>();
		File[] faDirs = project.getDirectories();
		for(File f : faDirs) llUnreadDirs.add(f);
		while(!llUnreadDirs.isEmpty())
		{
			File[] fa = llUnreadDirs.removeFirst().listFiles();
			for(File f : fa)
			{
				if(f.isDirectory())
				{
					llUnreadDirs.add(f);
				} else if(f.getName().endsWith(".java") || f.getName().endsWith(".class"))
				{
					hs.add(f);
				}				
			}
		}

		return hs;
	}

	private boolean addIcon() throws Exception
	{
		File iconFile = project.getIconFile();

		if(!project.getUseIcon() || iconFile == null) return true;
		if(!iconFile.exists()) throw new IOException("Windows icon file doesn't exist:\n" + iconFile.toString());

		File fTmp = File.createTempFile("icon", ".rc", outDir);
		FileWriter fw = new FileWriter(fTmp);
		fw.write("1 ICON \"" + iconFile.toString().replaceAll("\\\\", "/") + "\"\n");
		fw.flush();
		fw.close();

		String[] saCmd =
			{
				(new File(compilerPath, "bin/windres")).toString(),
				fTmp.toString(),
				fTmp.toString()+".o"
			};
		if(!runCmd(saCmd, "including icon", true)) return false;

		fTmp.delete();
		return true;
	}

	private boolean runCmd(String[] cmd, String logLine, boolean logInput) throws Exception
	{
		if(!suppressCommandLogging) logger.log("- " + logLine, false);

		if(!suppressCommandLogging && project.getShowCommands())
		{
			StringBuffer sb = new StringBuffer("[");
			for(int i=0; i<cmd.length; i++)
			{
				sb.append(cmd[i]);
				if(i+1 < cmd.length) sb.append("\n\t\t");
			}
			sb.append("]");
			logger.log(sb.toString(), true);
		}

		if(!(new File(cmd[0])).exists() && !(new File(cmd[0]+".exe")).exists())
		{
			logger.log("Can't run the command, \"" + cmd[0] + "\" doesn't exist!", true);
			return false;
		}

		Process p = Runtime.getRuntime().exec(cmd);
		if(logInput) log(p.getInputStream());
		log(p.getErrorStream()).join();
		return (p.waitFor() == 0);
	}

	private Thread log(final InputStream inputStream)
	{
		Thread t = new Thread()
		{
			public void run()
			{
				try
				{
					BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
					String line;

					while((line = br.readLine()) != null) logger.log(line, true);

					br.close();
				} catch(Exception ex)
				{
					ex.printStackTrace();
				}
			}
		};
		t.start();
		return t;
	}

	private void beep(final boolean error)
	{
		if(!project.getBeepWhenDone()) return;

		// There is no dispatcher in the AutoCompiler. So this beep
		// has to be called synchronously to work there too.
		Display.getDefault().syncExec(new Runnable()
				{
					public void run()
					{
						Display.getDefault().beep();
						if(error)
						{
							try
							{
								Thread.sleep(150);
							} catch (Exception e) { }

							Display.getDefault().beep();
						}
					}
				});
	}
}
