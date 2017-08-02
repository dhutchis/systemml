
args <- commandArgs(TRUE)
options(digits=22)

library(Matrix)
library("matrixStats")

k = 2
n = 2^3
n13 = as.integer(n^(1.0/3.0))
n23 = as.integer(n^(2.0/3.0))
nlog = as.integer(log(n,2)+1)

# random matrix of -1 and 1
# todo optimize to 0 and 1
#AR = rand(rows=nlog, cols=n, min=0, max=1, pdf="uniform", sparsity=1.0)
#A = -1 * (AR <= 0.5) + (AR > 0.5)
A = as.matrix(readMM(paste(args[1], "A.mtx", sep="")))


##########################################################
## Naive:
X = abs( t(A)%*%A - diag(t(A)%*%A) )
X_rowmax = rowMaxs(X) # col vector of max val in each row
X_rowidx = as.numeric( max.col(t(X_rowmax),ties.method="last") ) # row index of max val
XR = X[X_rowidx,] # row vector
X_colidx = as.numeric( which.max(XR) ) # col index of max val
# result: (X_rowidx, X_colidx)
##########################################################



Z = cbind(X_rowidx, X_colidx)

print(paste0("(i,j): ",X_rowidx, ",",X_colidx))
# print(X)
writeMM(as(Z, "CsparseMatrix"), paste(args[2], "O", sep=""));