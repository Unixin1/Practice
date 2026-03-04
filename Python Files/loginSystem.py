username = "admin"
password = "1234"
loginAttemp = 3

while loginAttemp > 0:

    enterUsername = input("Enter Username: ")
    enterPassword = input("Enter Password: ")

    if enterUsername == username and enterPassword == password:
        print("Login Successfully!")
        break
    else:
        loginAttemp -= 1
        print("Wrong credentials. Attempts left:", loginAttemp)

if loginAttemp == 0:
    print("Account Locked")
