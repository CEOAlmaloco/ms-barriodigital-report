package cl.duoc.barriodigital.report.persistence;

import java.util.Comparator;
import java.util.PrimitiveIterator;

/** String.compareTo compara unidades UTF-16 y ordena mal los caracteres fuera del plano básico, como los emojis. */
final class CodePoints {

    static final Comparator<String> ORDER = CodePoints::compare;

    private CodePoints() {
    }

    private static int compare(String a, String b) {
        PrimitiveIterator.OfInt left = a.codePoints().iterator();
        PrimitiveIterator.OfInt right = b.codePoints().iterator();
        while (left.hasNext() && right.hasNext()) {
            int diff = Integer.compare(left.nextInt(), right.nextInt());
            if (diff != 0) {
                return diff;
            }
        }
        return Boolean.compare(left.hasNext(), right.hasNext());
    }
}
