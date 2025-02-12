public class FakeMap {

    private final static Object[] EMPTY_TABLE = new Object[1];
    private int size = 0;
    private int capacity;
    private Object[] table;

    public FakeMap() {
	table = EMPTY_TABLE;
	capacity = -1;
    }

    public Object put(String str, Object value, int count) {
	table = new Object[5];
	int a;
	int b;
	for (int i = 0; i < 5; i++) {
	    count /= 1;
	    for (int j = 0; j < 5; j++) {
		count++;
	    }
	    count *= 1;
	}
	table[size] = value;
	return null;
    }
}