// IPixelXpertProxy.aidl
package sh.siava.pixelxpert;

// Root proxy service interface. The hook side runs inside the apps it hooks,
// so it reaches us over a binder rather than by calling in-process.
interface IPixelXpertProxy {
	String[] runRootCommand(String command);
	Bitmap extractSubject(in Bitmap input, int method);
}
