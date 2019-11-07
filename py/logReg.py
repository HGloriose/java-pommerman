import pandas as pd
from sklearn import preprocessing
from sklearn.model_selection import train_test_split
from sklearn.neighbors import KNeighborsClassifier
from sklearn.linear_model import LogisticRegression
import numpy as np

df = pd.read_csv('all-[vision7]-new.csv')
print(df)
df = df.dropna()
labels = df.Label
df = (df.drop(columns = ['Label']))/100
#data = pd.DataFrame(labels).join(df)
#data
X_train, X_test, y_train, y_test = train_test_split(df, labels, test_size = 0.3, shuffle = True)

model0 = LogisticRegression(solver = "newton-cg", multi_class ='multinomial')
fitted0 = model0.fit(X_train, y_train)
res0 = fitted0.score(X_train, y_train)
res0_test = fitted0.score(X_test, y_test)

co = fitted0.coef_
print(co)
print(res0, res0_test)

prediction = fitted0.predict(X_test)
print(prediction)

k =2
model = KNeighborsClassifier(n_neighbors=k)
print('1')
fitted = model.fit(X_train, y_train)
print('2')
res = fitted.score(X_train, y_train)
print('3')
test_res = fitted.score(X_test, y_test)
print('K:', k, 'Train_score: ', res, 'Test_score:', test_res)

