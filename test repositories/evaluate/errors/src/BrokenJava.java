
public class BrokenJava {
	public void error() {
		missingReference();
	}

	@Deprecated
	public void deprecated() {
		java.util.List<Integer> bla = new java.util.ArrayList();
	}

	public void warning() {
		deprecated();
	}
}