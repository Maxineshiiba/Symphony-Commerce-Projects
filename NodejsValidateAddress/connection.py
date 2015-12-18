#!/usr/bin/env python
import MySQLdb
from urlparse import urlparse
 
global db 
global cursor
global host 
global user
global passwd
global table

#staging conntection
# host = "us-cdbr-iron-east-02.cleardb.net"
# user = "bfa5e5e476498d"
# passwd = "418e35db"
# table = "heroku_6ed7fb897e88c63"

#production connection
host = "us-cdbr-iron-east-02.cleardb.net"
user = "b451fa2ec43467"
passwd = "2ffd4c2a"
table = "heroku_98a89ebd7fac3ea"

#localhost connection
# host = "localhost"
# user = "root"
# passwd = "passw0rd"
# table = "dmfulfillment"

#display all orderIDs in table
def display():
        try:
            db = MySQLdb.connect(host, user, passwd, table)
            cursor = db.cursor()
            cursor.execute("SELECT * FROM whiteListed_order;")
            order_ids = cursor.fetchall()
            print "Bypassed OrderIds: "
            for order_id in order_ids:
                print("{}".format (order_id[0]))
            cursor.close()
            db.close()
        except MySQLdb.Error as e:
            print (e)        

#insert multiple orderIDs 
def bypass():
    input = raw_input("Enter multiple orderIds you want to bypass, separated by commas: ")
    orderIDs_list = [order_id.strip(' ') for order_id in input.split(',')]
    integerCheck = all([order_id.replace(' ','').isdigit() for order_id in orderIDs_list])
    if(integerCheck):
        try:
            db = MySQLdb.connect(host, user, passwd, table)
            cursor = db.cursor()
            insert = "INSERT IGNORE INTO whiteListed_order" "(order_id)" "VALUES (%(order_id)s)"     
            cursor.executemany(insert, [{'order_id': order_id} for order_id in orderIDs_list])
            db.commit()
            print "We will not do address check for the following orderIds: {}".format(orderIDs_list) 
        except MySQLdb.Error as e:
            db.rollback()
            print (e)
    else:
        print "Your entry contains invalid OrderId."
    cursor.close()
    db.close()

#delete multiple orderIDs
def nobypass():
    input = raw_input("Enter multiple orderIds you don't want to bypass, separated by commas: ")
    orderIDs_list = [order_id.strip(' ') for order_id in input.split(',')]
    integerCheck = all([order_id.replace(' ','').isdigit() for order_id in orderIDs_list])
    if(integerCheck):
        try:
            db = MySQLdb.connect(host, user, passwd, table)
            cursor = db.cursor()
            delete = "DELETE FROM whiteListed_order WHERE order_id in (%(order_id)s)"
            orderId = {'order_id':order_id}
            cursor.executemany(delete, [{'order_id': order_id} for order_id in orderIDs_list])
            db.commit()
            print "We will do address check for the following orderIds:{}".format(orderIDs_list) 
        except MySQLdb.Error as e:
            db.rollback()
            print(e)
    else:    
        print "Your entry contains invalid OrderId"
    cursor.close()
    db.close()

#delete all orderIDs 
def delete_all():
    input = raw_input("Are you sure you want to delete all OrderIDs? [Y/N] ")
    if input == 'Y' or input == 'y':
        try:
            db = MySQLdb.connect(host, user, passwd, table)
            cursor = db.cursor()
            delete = "TRUNCATE TABLE whiteListed_order "    
            cursor.execute(delete)
            db.commit()
            print "All OrderIDs have been removed from database."
        except MySQLdb.Error as e:
            db.rollback()
            print "Error: unable to delete all records"           
    cursor.close()
    db.close()

def notAfun():
    print "Error: not a valid function name, please enter again"    

def main():
    while True:         
        choice = raw_input('choose from: bypass, nobypass or quit: ')
        if choice == "quit":
            break   
        {
        'bypass': bypass,
        'nobypass': nobypass
        }.get(choice, notAfun)()

if __name__ == '__main__':
    main()
