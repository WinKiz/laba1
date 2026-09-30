import java.util.Arrays;
import java.util.Locale;

public class Lab1 {

    // Параметры задачи
    static final double A = 0.0;          // левая граница
    static final double B = Math.PI;      // правая граница
    static final double EXACT = 2.0;      // точное значение интеграла sin(x) на [0, pi]
    static final double EPS = 1e-10;      // допустимая погрешность проверки корректности

    // Параметры методики измерений
    static int warmupRuns = 2;            // прогревочные запуски (JVM/JIT), не учитываются
    static int measureRuns = 5;           // количество учитываемых измерений

    // Исследуемые параметры
    static long[] nValues = {10_000_000L, 100_000_000L, 1_000_000_000L}; // объём разбиения N
    static int[] threadCounts = {1, 2, 4, 8, 16, 32, 64};                // количества потоков

    // Интегрируемая функция
    static double f(double x) {
        return Math.sin(x);
    }

    // Последовательная реализация (эталон)
    static double integrateSequential(double a, double b, long n) {
        double h = (b - a) / n;
        double sum = 0.0;
        for (long i = 0; i < n; i++) {
            sum += f(a + (i + 0.5) * h);   // значение в середине i-го отрезка
        }
        return sum * h;
    }

    // Задача потока: считает локальную сумму на своём диапазоне
    static final class SumWorker extends Thread {
        private final double a, h;
        private final long start, end;
        double partial;                    // локальная сумма потока

        SumWorker(double a, double h, long start, long end) {
            this.a = a;
            this.h = h;
            this.start = start;
            this.end = end;
        }

        @Override
        public void run() {
            double local = 0.0;
            for (long i = start; i < end; i++) {
                local += f(a + (i + 0.5) * h);
            }
            partial = local;
        }
    }

    // Параллельная реализация: делим диапазон между потоками, объединяем локальные суммы
    static double integrateParallel(double a, double b, long n, int threads)
            throws InterruptedException {
        double h = (b - a) / n;
        SumWorker[] workers = new SumWorker[threads];
        // запуск потоков, каждому свой непрерывный диапазон [n*t/threads, n*(t+1)/threads)
        for (int t = 0; t < threads; t++) {
            workers[t] = new SumWorker(a, h, n * t / threads, n * (t + 1) / threads);
            workers[t].start();
        }
        double total = 0.0;
        // ожидание завершения потоков и объединение частичных результатов
        for (SumWorker w : workers) {
            w.join();
            total += w.partial;
        }
        return total * h;
    }

    // Функциональный интерфейс, чтобы измерять обе реализации одним методом
    interface Integrator {
        double integrate(double a, double b, long n, int threads) throws InterruptedException;
    }

    // Результат измерения: медиана, минимум и полученное значение
    static final class Stats {
        final double median, min, result;
        Stats(double median, double min, double result) {
            this.median = median;
            this.min = min;
            this.result = result;
        }
    }

    // Измерение: прогрев, measureRuns запусков, медиана и минимум времени
    static Stats measure(Integrator integrator, long n, int threads) throws InterruptedException {
        for (int i = 0; i < warmupRuns; i++) {
            integrator.integrate(A, B, n, threads);
        }
        double[] times = new double[measureRuns];
        double result = 0.0;
        for (int i = 0; i < measureRuns; i++) {
            long start = System.nanoTime();
            result = integrator.integrate(A, B, n, threads);
            times[i] = (System.nanoTime() - start) / 1_000_000_000.0;
        }
        double[] sorted = times.clone();
        Arrays.sort(sorted);
        double median = (measureRuns % 2 == 1)
                ? sorted[measureRuns / 2]
                : 0.5 * (sorted[measureRuns / 2 - 1] + sorted[measureRuns / 2]);
        return new Stats(median, sorted[0], result);
    }

    // Эксперимент для одного значения N: посл. версия + все количества потоков
    static void runForN(long n) throws InterruptedException {
        // последовательная версия (для справки, объём работы тот же)
        Stats seq = measure((a, b, nn, t) -> integrateSequential(a, b, nn), n, 1);

        System.out.printf(Locale.ROOT, "N=%d%n", n);
        System.out.printf(Locale.ROOT,
                "последовательно: время=%.6f с, мин=%.6f с, результат=%.12f, %s%n",
                seq.median, seq.min, seq.result,
                Math.abs(seq.result - EXACT) < EPS ? "OK" : "НЕТ");

        // измерения параллельной версии для каждого количества потоков
        Stats[] par = new Stats[threadCounts.length];
        double t1 = seq.median;            // база для ускорения
        for (int i = 0; i < threadCounts.length; i++) {
            par[i] = measure(Lab1::integrateParallel, n, threadCounts[i]);
            if (threadCounts[i] == 1) {
                t1 = par[i].median;        // ускорение считаем от 1 потока (строка 1 -> 1.00)
            }
        }

        System.out.printf(Locale.ROOT, "%-8s %-12s %-12s %-12s %-14s %-6s%n",
                "потоки", "время_с", "мин_с", "ускорение", "эффективность", "корр");
        // вывод таблицы: ускорение S = T1/Tp, эффективность E = S/p*100%
        for (int i = 0; i < threadCounts.length; i++) {
            int p = threadCounts[i];
            double speedup = t1 / par[i].median;
            printRow(p, par[i].median, par[i].min, speedup, speedup / p * 100.0, par[i].result);
        }
        System.out.println();
    }

    // Печать одной строки таблицы
    static void printRow(int threads, double time, double min,
                         double speedup, double efficiency, double result) {
        System.out.printf(Locale.ROOT, "%-8d %-12.6f %-12.6f %-12.3f %-14.2f %-6s%n",
                threads, time, min, speedup, efficiency,
                Math.abs(result - EXACT) < EPS ? "OK" : "НЕТ");
    }

    // Разбор аргументов командной строки
    static void parseArgs(String[] args) {
        for (String arg : args) {
            if (arg.equals("--quick")) {
                nValues = new long[]{1_000_000L, 10_000_000L};
                threadCounts = new int[]{1, 2, 4, 8};
                measureRuns = 3;
                warmupRuns = 1;
            } else if (arg.startsWith("--n=")) {
                String[] parts = arg.substring(4).split(",");
                nValues = new long[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    nValues[i] = Long.parseLong(parts[i].trim());
                }
            } else if (arg.startsWith("--threads=")) {
                String[] parts = arg.substring(10).split(",");
                threadCounts = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    threadCounts[i] = Integer.parseInt(parts[i].trim());
                }
            } else if (arg.startsWith("--runs=")) {
                measureRuns = Integer.parseInt(arg.substring(7));
            } else if (arg.startsWith("--warmups=")) {
                warmupRuns = Integer.parseInt(arg.substring(10));
            }
        }
    }

    // Точка входа
    public static void main(String[] args) throws InterruptedException {
        parseArgs(args);
        System.out.printf(Locale.ROOT, "ядер=%d прогрев=%d измерений=%d%n",
                Runtime.getRuntime().availableProcessors(), warmupRuns, measureRuns);
        for (long n : nValues) {
            runForN(n);
        }
    }
}
