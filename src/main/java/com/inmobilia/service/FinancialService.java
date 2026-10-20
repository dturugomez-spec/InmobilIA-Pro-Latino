package com.inmobilia.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Motor de calculos financieros.
 * Port de: pg_dcf, pg_montecarlo, pg_stress, amortizacion_frances, amortizacion_aleman,
 * pg_hipoteca, pg_yield, pg_tanteo, pg_waterfall, IRR/NPV numericos.
 */
@Service
public class FinancialService {

    // ─────────────────── HIPOTECA / AMORTIZACION ───────────────────

    public record CuotaMes(int mes, double cuota, double interes, double amortizacion, double saldo) {}

    public List<CuotaMes> amortizacionFrances(double capital, double taeAnual, int anios) {
        int n = anios * 12;
        double r = taeAnual / 100.0 / 12.0;
        double cuota;
        if (r == 0) cuota = capital / n;
        else        cuota = capital * (r * Math.pow(1 + r, n)) / (Math.pow(1 + r, n) - 1);
        List<CuotaMes> rows = new ArrayList<>(n);
        double saldo = capital;
        for (int i = 1; i <= n; i++) {
            double interes = saldo * r;
            double amort   = cuota - interes;
            saldo = Math.max(0, saldo - amort);
            rows.add(new CuotaMes(i, round(cuota), round(interes), round(amort), round(saldo)));
        }
        return rows;
    }

    public List<CuotaMes> amortizacionAleman(double capital, double taeAnual, int anios) {
        int n = anios * 12;
        double r = taeAnual / 100.0 / 12.0;
        double amort = capital / n;
        List<CuotaMes> rows = new ArrayList<>(n);
        double saldo = capital;
        for (int i = 1; i <= n; i++) {
            double interes = saldo * r;
            double cuota   = amort + interes;
            saldo = Math.max(0, saldo - amort);
            rows.add(new CuotaMes(i, round(cuota), round(interes), round(amort), round(saldo)));
        }
        return rows;
    }

    /** Cuota mensual amortizable (sistema frances). */
    public double cuotaMensualFrances(double principal, double taeAnual, int anios) {
        double r = taeAnual / 100.0 / 12.0;
        int n = anios * 12;
        if (r > 0 && principal > 0) return principal * r * Math.pow(1 + r, n) / (Math.pow(1 + r, n) - 1);
        if (principal > 0)          return principal / n;
        return 0;
    }

    /** Pago anual amortizable (helper para stress test). */
    public double amortizingAnnualPayment(double principal, double taeAnual, int anios) {
        if (principal <= 0) return 0;
        double r = taeAnual / 100.0 / 12.0;
        int n = anios * 12;
        if (r > 0) return principal * r * Math.pow(1 + r, n) / (Math.pow(1 + r, n) - 1) * 12;
        return principal / (double) anios;
    }

    // ─────────────────── IRR / NPV ───────────────────

    /** Calculo de IRR (Newton-Raphson). Devuelve Double.NaN si no converge. */
    public double irr(double[] cashflows) {
        return irr(cashflows, 0.1);
    }

    public double irr(double[] cashflows, double guess) {
        double rate = guess;
        for (int it = 0; it < 200; it++) {
            double npv = 0;
            double dnpv = 0;
            for (int t = 0; t < cashflows.length; t++) {
                double denom = Math.pow(1 + rate, t);
                npv  += cashflows[t] / denom;
                dnpv += -t * cashflows[t] / (denom * (1 + rate));
            }
            if (Math.abs(npv) < 1e-7) return rate;
            if (dnpv == 0) break;
            double newRate = rate - npv / dnpv;
            if (Math.abs(newRate - rate) < 1e-9) return newRate;
            rate = newRate;
            if (rate < -0.999) rate = -0.999;
            if (rate > 10)     rate = 10;
        }
        // fallback: barrido por biseccion
        return irrBisection(cashflows);
    }

    private double irrBisection(double[] cfs) {
        double low = -0.99, high = 10.0;
        double lowNpv = npv(cfs, low);
        double highNpv = npv(cfs, high);
        if (lowNpv * highNpv > 0) return Double.NaN;
        for (int i = 0; i < 200; i++) {
            double mid = (low + high) / 2;
            double midNpv = npv(cfs, mid);
            if (Math.abs(midNpv) < 1e-7) return mid;
            if (midNpv * lowNpv < 0) { high = mid; highNpv = midNpv; }
            else                     { low = mid; lowNpv = midNpv; }
        }
        return (low + high) / 2;
    }

    /** Valor actual neto dado una tasa (como fraccion, no %). */
    public double npv(double[] cashflows, double rate) {
        double acc = 0;
        for (int t = 0; t < cashflows.length; t++) {
            acc += cashflows[t] / Math.pow(1 + rate, t);
        }
        return acc;
    }

    // ─────────────────── DCF INSTITUCIONAL ───────────────────

    public record DcfInputs(
            double price, double rentMonthly, double vacancyPct, double opexPct, int years,
            double growthPct, double growthExpPct, double exitCapPct, double discountPct,
            double buyingCostsPct, double ltvPct, double interestPct, int loanTerm, double capexPct) {}

    public record DcfYear(int year, double egi, double opex, double noi, double capex,
                          double cfBeforeDebt, double debtSvc, double cfEquity, double debtBalance, double dscr) {}

    public record DcfOutputs(List<DcfYear> years, double equityInvested, double debt, double buyingCost,
                             double totalCost, double annualDebtSvc, double terminal, double npv,
                             Double irrLevered, Double irrUnlevered, double coc, double dscrYr1,
                             double capRateIn, double moic, double equityProfit) {}

    public DcfOutputs computeDcf(DcfInputs in) {
        double rentYear    = in.rentMonthly() * 12;
        double buyingCost  = in.price() * in.buyingCostsPct() / 100.0;
        double totalCost   = in.price() + buyingCost;
        double equity      = totalCost * (1 - in.ltvPct() / 100.0);
        double debt        = in.price() * in.ltvPct() / 100.0;

        // Debt service (frances)
        double rMonth = in.interestPct() / 100.0 / 12.0;
        int nMonth = in.loanTerm() * 12;
        double monthlyPmt;
        if (rMonth > 0 && debt > 0) monthlyPmt = debt * rMonth * Math.pow(1 + rMonth, nMonth) / (Math.pow(1 + rMonth, nMonth) - 1);
        else if (debt > 0)          monthlyPmt = debt / nMonth;
        else                        monthlyPmt = 0;
        double annualDs = monthlyPmt * 12;

        // Annual debt-balance schedule
        double debtBal = debt;
        double[] debtBalances = new double[in.years()];
        for (int y = 0; y < in.years(); y++) {
            for (int m = 0; m < 12; m++) {
                double intPart = debtBal * rMonth;
                double capPart = monthlyPmt - intPart;
                debtBal = Math.max(debtBal - capPart, 0);
            }
            debtBalances[y] = debtBal;
        }

        List<DcfYear> years = new ArrayList<>(in.years());
        double[] cfsEquity = new double[in.years()];
        double[] noiArr    = new double[in.years()];
        double[] capexArr  = new double[in.years()];
        for (int y = 1; y <= in.years(); y++) {
            double rent  = rentYear * Math.pow(1 + in.growthPct() / 100.0, y - 1);
            double egi   = rent * (1 - in.vacancyPct() / 100.0);
            double opex  = egi * in.opexPct() / 100.0 * Math.pow(1 + in.growthExpPct() / 100.0, y - 1);
            double noi   = egi - opex;
            double capex = egi * in.capexPct() / 100.0;
            double cfBefore = noi - capex;
            double cfEq     = cfBefore - annualDs;
            cfsEquity[y - 1] = cfEq;
            noiArr[y - 1]    = noi;
            capexArr[y - 1]  = capex;
            double dscr = annualDs > 0 ? cfBefore / annualDs : 999;
            years.add(new DcfYear(y, round(egi), round(opex), round(noi), round(capex),
                    round(cfBefore), round(annualDs), round(cfEq), round(debtBalances[y - 1]), round(dscr)));
        }

        double terminal    = noiArr[noiArr.length - 1] * (1 + in.growthPct() / 100.0) / (in.exitCapPct() / 100.0);
        double netTerminal = terminal - debtBalances[debtBalances.length - 1];
        cfsEquity[cfsEquity.length - 1] += netTerminal;

        // NPV equity
        double pvTotal = 0;
        for (int i = 0; i < cfsEquity.length; i++) pvTotal += cfsEquity[i] / Math.pow(1 + in.discountPct() / 100.0, i + 1);
        double npv = pvTotal - equity;

        // IRR levered
        double[] irrLevFlows = new double[cfsEquity.length + 1];
        irrLevFlows[0] = -equity;
        System.arraycopy(cfsEquity, 0, irrLevFlows, 1, cfsEquity.length);
        Double irrLev = finite(irr(irrLevFlows));

        // IRR unlevered
        double[] unlevered = new double[noiArr.length + 1];
        unlevered[0] = -totalCost;
        for (int i = 0; i < noiArr.length; i++) unlevered[i + 1] = noiArr[i] - capexArr[i];
        unlevered[unlevered.length - 1] += terminal;
        Double irrUl = finite(irr(unlevered));

        double coc     = equity > 0 ? cfsEquity[0] / equity * 100 : 0;
        double dscrYr1 = annualDs > 0 ? (noiArr[0] - capexArr[0]) / annualDs : Double.POSITIVE_INFINITY;
        double capIn   = noiArr[0] / in.price() * 100;

        double totalDistrib = 0;
        for (double c : cfsEquity) totalDistrib += c;
        double moic = equity > 0 ? (equity + totalDistrib) / equity : 0;

        return new DcfOutputs(years, equity, debt, buyingCost, totalCost, annualDs,
                terminal, npv, irrLev, irrUl, coc, dscrYr1, capIn, moic, totalDistrib);
    }

    // ─────────────────── MONTE CARLO ───────────────────

    public record MonteCarloInputs(double investment, double noiBase, int years,
                                    double volNoiPct, double volCapPct, double capBasePct,
                                    double growthBasePct, int simulations, double irrTargetPct) {}

    public record MonteCarloOutputs(double[] irrs, double[] terminals, double[] npvs,
                                    double irrMean, double irrMedian, double irrStd,
                                    double var95, double cvar95, double probLoss, double probTarget,
                                    double p10, double p25, double p50, double p75, double p90, double p95,
                                    double skew, double kurt, double terminalMean) {}

    public MonteCarloOutputs monteCarlo(MonteCarloInputs in) {
        java.util.Random rnd = new java.util.Random();
        List<Double> irrs = new ArrayList<>();
        List<Double> terminals = new ArrayList<>();
        List<Double> npvs = new ArrayList<>();

        for (int s = 0; s < in.simulations(); s++) {
            double growthShock = in.growthBasePct() / 100.0 + rnd.nextGaussian() * 0.005;
            double capShock    = Math.max(in.capBasePct() * Math.exp(rnd.nextGaussian() * (in.volCapPct() / 100.0 / 2.0)), 1.0);
            double[] cfs = new double[in.years()];
            double noi = in.noiBase();
            for (int i = 0; i < in.years(); i++) {
                double noiShock = 1 + rnd.nextGaussian() * (in.volNoiPct() / 100.0);
                noi = noi * (1 + growthShock) * noiShock;
                cfs[i] = noi;
            }
            double terminal = cfs[cfs.length - 1] / (capShock / 100.0);
            terminals.add(terminal);
            cfs[cfs.length - 1] += terminal;
            double[] full = new double[cfs.length + 1];
            full[0] = -in.investment();
            System.arraycopy(cfs, 0, full, 1, cfs.length);
            double irrVal = irr(full) * 100;
            if (!Double.isNaN(irrVal) && irrVal > -50 && irrVal < 200) {
                irrs.add(irrVal);
                double npvVal = 0;
                for (int i = 0; i < cfs.length; i++) npvVal += cfs[i] / Math.pow(1.08, i + 1);
                npvVal -= in.investment();
                npvs.add(npvVal);
            }
        }
        if (irrs.isEmpty()) {
            return new MonteCarloOutputs(new double[0], new double[0], new double[0],
                    0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);
        }
        double[] irrArr = irrs.stream().mapToDouble(Double::doubleValue).toArray();
        double[] termArr = terminals.stream().mapToDouble(Double::doubleValue).toArray();
        double[] npvArr  = npvs.stream().mapToDouble(Double::doubleValue).toArray();
        java.util.Arrays.sort(irrArr);
        double mean   = StatUtils.mean(irrArr);
        double std    = StatUtils.std(irrArr);
        double med    = StatUtils.percentile(irrArr, 50);
        double var95  = StatUtils.percentile(irrArr, 5);
        double cvar95 = StatUtils.mean(java.util.Arrays.stream(irrArr).filter(v -> v <= var95).toArray());
        long losses   = java.util.Arrays.stream(irrArr).filter(v -> v < 0).count();
        long tgt      = java.util.Arrays.stream(irrArr).filter(v -> v >= in.irrTargetPct()).count();
        return new MonteCarloOutputs(irrArr, termArr, npvArr, mean, med, std, var95, cvar95,
                losses * 100.0 / irrArr.length, tgt * 100.0 / irrArr.length,
                StatUtils.percentile(irrArr, 10), StatUtils.percentile(irrArr, 25),
                StatUtils.percentile(irrArr, 50), StatUtils.percentile(irrArr, 75),
                StatUtils.percentile(irrArr, 90), StatUtils.percentile(irrArr, 95),
                StatUtils.skewness(irrArr), StatUtils.kurtosis(irrArr),
                StatUtils.mean(termArr));
    }

    // ─────────────────── STRESS TEST DSCR ───────────────────

    public record StressInputs(double noi, double debt, double rate, int term, double dscrMin, double noiStressPct) {}
    public record StressScenario(String nombre, double noi, double pmt, double dscr) {}

    public List<StressScenario> stressTest(StressInputs in) {
        double pmtBase = amortizingAnnualPayment(in.debt(), in.rate(), in.term());
        double noiShocked = in.noi() * (1 - in.noiStressPct() / 100.0);
        List<StressScenario> out = new ArrayList<>();
        out.add(new StressScenario("Base",                    in.noi(), pmtBase, pmtBase > 0 ? in.noi() / pmtBase : 0));
        out.add(new StressScenario("NOI shock -" + in.noiStressPct() + "%", noiShocked, pmtBase, pmtBase > 0 ? noiShocked / pmtBase : 0));
        // Subidas de tipos
        double pmtUp100 = amortizingAnnualPayment(in.debt(), in.rate() + 1.0, in.term());
        double pmtUp200 = amortizingAnnualPayment(in.debt(), in.rate() + 2.0, in.term());
        out.add(new StressScenario("Tipos +100bp",  in.noi(), pmtUp100, pmtUp100 > 0 ? in.noi() / pmtUp100 : 0));
        out.add(new StressScenario("Tipos +200bp",  in.noi(), pmtUp200, pmtUp200 > 0 ? in.noi() / pmtUp200 : 0));
        out.add(new StressScenario("Combinado NOI + Tipos +200bp", noiShocked, pmtUp200,
                pmtUp200 > 0 ? noiShocked / pmtUp200 : 0));
        return out;
    }

    // ─────────────────── YIELD / QUICK CHECK ───────────────────

    public record YieldResult(double grossYield, double netYield, double capRate, double cashOnCash,
                              double noiMonthly, double cashflowMonthly) {}

    public YieldResult computeYield(double price, double rentMonthly, double expensesMonthly,
                                     double mortgageMonthly, double equity) {
        double grossYield = price > 0 ? rentMonthly * 12 / price * 100 : 0;
        double noiMonthly = rentMonthly - expensesMonthly;
        double noiYear    = noiMonthly * 12;
        double netYield   = price > 0 ? noiYear / price * 100 : 0;
        double capRate    = netYield; // mismo concepto aqui
        double cf         = noiMonthly - mortgageMonthly;
        double cashOnCash = equity > 0 ? cf * 12 / equity * 100 : 0;
        return new YieldResult(grossYield, netYield, capRate, cashOnCash, noiMonthly, cf);
    }

    // ─────────────────── WATERFALL (estructura distribucion) ───────────────────

    public record WaterfallTier(String nombre, double pct, double threshold) {}
    public record WaterfallResult(List<Map<String, Object>> distribuciones, double totalLp, double totalGp) {}

    /** Waterfall simplificada con tiers (preferred return + promote). */
    public WaterfallResult waterfall(double equityLp, double equityGp, double[] cashflows,
                                      double preferredRate, List<WaterfallTier> tiers) {
        double totalEquity = equityLp + equityGp;
        double lpShare = totalEquity > 0 ? equityLp / totalEquity : 1;
        double gpShare = 1 - lpShare;

        List<Map<String, Object>> dist = new ArrayList<>();
        double totalLp = 0, totalGp = 0;
        double prefBase = equityLp;

        for (int i = 0; i < cashflows.length; i++) {
            double cf = cashflows[i];
            double pref = prefBase * preferredRate / 100.0;
            double paidPref = Math.min(cf, pref);
            double remaining = cf - paidPref;
            double lp = paidPref;
            double gp = 0;
            // aplicar tiers secuenciales sobre remaining
            for (WaterfallTier tier : tiers) {
                if (remaining <= 0) break;
                double take = remaining * tier.pct() / 100.0;
                lp += remaining - take;
                gp += take;
                remaining = 0;
            }
            if (remaining > 0) {
                lp += remaining * lpShare;
                gp += remaining * gpShare;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Ano", i + 1);
            row.put("Cashflow", round(cf));
            row.put("LP", round(lp));
            row.put("GP", round(gp));
            dist.add(row);
            totalLp += lp;
            totalGp += gp;
        }
        return new WaterfallResult(dist, round(totalLp), round(totalGp));
    }

    // ─────────────────── HELPERS ───────────────────

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }

    private static Double finite(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return v;
    }
}
