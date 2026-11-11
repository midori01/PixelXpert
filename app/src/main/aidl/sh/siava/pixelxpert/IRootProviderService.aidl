// IRootProviderService.aidl
package sh.siava.pixelxpert;

// Root provider service interface, implemented by RootProvider - a libsu
// RootService, so every call here crosses into a root process.
interface IRootProviderService {
	boolean checkLSPosedDB(String packageName);
	boolean isPackageInstalled(String packageName);
	boolean activateInLSPosed(String packageName);
	IBinder getFileSystemService();
}
