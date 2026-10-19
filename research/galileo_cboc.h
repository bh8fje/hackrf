#ifndef GALILEO_CBOC_H
#define GALILEO_CBOC_H
#include <cmath>
#include "ComplexNumber.h"

// Galileo OS SIS ICD 2.2, Eq.11; phase is measured in half primary-code chips.
inline complex_number GalileoCboc(double phase,complex_number data,complex_number pilot) {
    constexpr double alpha=0.9534625892455924,beta=0.30151134457776363;
    double boc1=(int(std::floor(phase))&1)?-1.:1.;
    double boc6=(int(std::floor(phase*6))&1)?-1.:1.;
    return data*(alpha*boc1+beta*boc6)+pilot*(alpha*boc1-beta*boc6);
}
#endif
